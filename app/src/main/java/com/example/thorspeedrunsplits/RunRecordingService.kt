package com.example.thorspeedrunsplits

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class RunRecordingService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var outputDocumentUri: Uri? = null
    private var outputFileDescriptor: ParcelFileDescriptor? = null
    private var gameName = "Run"
    private var category = "Any%"
    private var recordingStartedAt = 0L
    private var isCleaningUp = false
    private var requestedRunLengthMillis: Long? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording(requestedRunLengthMillis ?: elapsedRecordingMillis())
        }
    }

    private val delayedStop = Runnable {
        stopRecording(requestedRunLengthMillis ?: elapsedRecordingMillis())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> scheduleStop(intent.getLongExtra(EXTRA_RUN_LENGTH_MILLIS, 0L))
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleStop(elapsedRecordingMillis())
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(delayedStop)
        stopRecording(requestedRunLengthMillis ?: elapsedRecordingMillis())
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        mainHandler.removeCallbacks(delayedStop)
        if (mediaRecorder != null || mediaProjection != null) {
            stopRecording(requestedRunLengthMillis ?: elapsedRecordingMillis(), stopService = false)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        if (projectionData == null) {
            stopSelf()
            return
        }

        gameName = intent.getStringExtra(EXTRA_GAME_NAME).orEmpty().ifBlank { "Run" }
        category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty().ifBlank { "Any%" }
        requestedRunLengthMillis = null

        try {
            val metrics = defaultDisplayMetrics()
            val resolutionWidth = intent.getIntExtra(
                EXTRA_RESOLUTION_WIDTH,
                metrics.widthPixels
            )
            val resolutionHeight = intent.getIntExtra(
                EXTRA_RESOLUTION_HEIGHT,
                metrics.heightPixels
            )
            val bitrateBitsPerSecond = intent.getIntExtra(
                EXTRA_BITRATE_BITS_PER_SECOND,
                DEFAULT_BITRATE_BITS_PER_SECOND
            ).coerceIn(MIN_BITRATE_BITS_PER_SECOND, MAX_BITRATE_BITS_PER_SECOND)
            val (width, height) = fitResolutionToDisplay(
                requestedWidth = resolutionWidth,
                requestedHeight = resolutionHeight,
                nativeWidth = metrics.widthPixels,
                nativeHeight = metrics.heightPixels
            )
            prepareOutput(intent.getStringExtra(EXTRA_FOLDER_URI))
            val recorder = createRecorder(width, height, bitrateBitsPerSecond)
            mediaRecorder = recorder

            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(Activity.RESULT_OK, projectionData)
                ?: error("Screen capture permission is unavailable.")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)
            virtualDisplay = projection.createVirtualDisplay(
                "ThorSpeedrunRunRecording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                mainHandler
            )
            recorder.start()
            recordingStartedAt = SystemClock.elapsedRealtime()
            isRecording = true
            broadcastRecordingState(active = true)
        } catch (_: Exception) {
            discardOutput()
            stopRecording(0L)
        }
    }

    private fun scheduleStop(runLengthMillis: Long) {
        if (mediaRecorder == null && mediaProjection == null) {
            stopSelf()
            return
        }
        requestedRunLengthMillis = runLengthMillis.coerceAtLeast(0L)
        mainHandler.removeCallbacks(delayedStop)
        mainHandler.postDelayed(delayedStop, RECORDING_TAIL_MILLIS)
    }

    private fun createRecorder(
        width: Int,
        height: Int,
        bitrateBitsPerSecond: Int
    ): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        outputFileDescriptor?.let { recorder.setOutputFile(it.fileDescriptor) }
            ?: recorder.setOutputFile(requireNotNull(outputFile).absolutePath)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(width, height)
        recorder.setVideoFrameRate(60)
        recorder.setVideoEncodingBitRate(bitrateBitsPerSecond)
        recorder.prepare()
        return recorder
    }

    private fun prepareOutput(folderUriString: String?) {
        val temporaryName = "ThorSpeedrun_${System.currentTimeMillis()}_recording.mp4"
        if (!folderUriString.isNullOrBlank()) {
            val treeUri = Uri.parse(folderUriString)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            outputDocumentUri = DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                "video/mp4",
                temporaryName
            ) ?: error("The recording file could not be created.")
            outputFileDescriptor = contentResolver.openFileDescriptor(
                requireNotNull(outputDocumentUri),
                "w"
            ) ?: error("The recording file could not be opened.")
        } else {
            val moviesRoot = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val recordingsDirectory = File(moviesRoot, "Run Recordings").apply { mkdirs() }
            outputFile = File(recordingsDirectory, temporaryName)
        }
    }

    private fun stopRecording(runLengthMillis: Long, stopService: Boolean = true) {
        if (isCleaningUp) return
        isCleaningUp = true
        mainHandler.removeCallbacks(delayedStop)

        val recorder = mediaRecorder
        mediaRecorder = null
        runCatching { recorder?.stop() }
            .onFailure { discardOutput() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        virtualDisplay?.release()
        virtualDisplay = null
        val projection = mediaProjection
        mediaProjection = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        outputFileDescriptor?.close()
        outputFileDescriptor = null

        finalizeOutputName(runLengthMillis)
        recordingStartedAt = 0L
        requestedRunLengthMillis = null
        isRecording = false
        broadcastRecordingState(active = false)
        isCleaningUp = false
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finalizeOutputName(runLengthMillis: Long) {
        val finalName = buildRecordingFileName(
            gameName = gameName,
            category = category,
            runLengthMillis = runLengthMillis,
            completedAtMillis = System.currentTimeMillis()
        )
        outputFile?.let { temporaryFile ->
            if (temporaryFile.exists()) {
                temporaryFile.renameTo(File(temporaryFile.parentFile, finalName))
            }
        }
        outputDocumentUri?.let { uri ->
            runCatching { DocumentsContract.renameDocument(contentResolver, uri, finalName) }
        }
        outputFile = null
        outputDocumentUri = null
    }

    private fun discardOutput() {
        outputFile?.delete()
        outputDocumentUri?.let { uri ->
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
        }
        outputFile = null
        outputDocumentUri = null
        outputFileDescriptor?.close()
        outputFileDescriptor = null
    }

    private fun elapsedRecordingMillis(): Long {
        return if (recordingStartedAt == 0L) 0L
        else SystemClock.elapsedRealtime() - recordingStartedAt
    }

    private fun fitResolutionToDisplay(
        requestedWidth: Int,
        requestedHeight: Int,
        nativeWidth: Int,
        nativeHeight: Int
    ): Pair<Int, Int> {
        val safeRequestedWidth = requestedWidth.coerceAtLeast(2)
        val safeRequestedHeight = requestedHeight.coerceAtLeast(2)
        val safeNativeWidth = nativeWidth.coerceAtLeast(2)
        val safeNativeHeight = nativeHeight.coerceAtLeast(2)
        val scale = minOf(
            1f,
            safeNativeWidth.toFloat() / safeRequestedWidth,
            safeNativeHeight.toFloat() / safeRequestedHeight
        )
        val width = (safeRequestedWidth * scale).roundToInt()
            .coerceAtLeast(2)
            .let { it - (it % 2) }
        val height = (safeRequestedHeight * scale).roundToInt()
            .coerceAtLeast(2)
            .let { it - (it % 2) }
        return width to height
    }

    @Suppress("DEPRECATION")
    private fun defaultDisplayMetrics(): DisplayMetrics {
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("The default display is unavailable.")
        return DisplayMetrics().also(display::getRealMetrics)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Run recording",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Recording speedrun")
            .setContentText("Capturing the internal display")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun broadcastRecordingState(active: Boolean) {
        sendBroadcast(
            Intent(ACTION_RECORDING_STATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_RECORDING_ACTIVE, active)
            }
        )
    }

    companion object {
        const val ACTION_RECORDING_STATE =
            "com.example.thorspeedrunsplits.RECORDING_STATE"
        const val EXTRA_RECORDING_ACTIVE = "recording_active"
        @Volatile
        var isRecording: Boolean = false

        private const val ACTION_START = "com.example.thorspeedrunsplits.START_RECORDING"
        private const val ACTION_STOP = "com.example.thorspeedrunsplits.STOP_RECORDING"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val EXTRA_GAME_NAME = "game_name"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_FOLDER_URI = "folder_uri"
        private const val EXTRA_RESOLUTION_WIDTH = "resolution_width"
        private const val EXTRA_RESOLUTION_HEIGHT = "resolution_height"
        private const val EXTRA_BITRATE_BITS_PER_SECOND = "bitrate_bits_per_second"
        private const val EXTRA_RUN_LENGTH_MILLIS = "run_length_millis"
        private const val NOTIFICATION_CHANNEL_ID = "run_recording"
        private const val NOTIFICATION_ID = 6006
        private const val RECORDING_TAIL_MILLIS = 3_000L
        private const val DEFAULT_BITRATE_BITS_PER_SECOND = 10_000_000
        private const val MIN_BITRATE_BITS_PER_SECOND = 2_000_000
        private const val MAX_BITRATE_BITS_PER_SECOND = 16_000_000

        fun start(
            context: Context,
            projectionData: Intent,
            gameName: String,
            category: String,
            folderUri: String?,
            resolutionWidth: Int,
            resolutionHeight: Int,
            bitrateBitsPerSecond: Int
        ) {
            val intent = Intent(context, RunRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
                putExtra(EXTRA_GAME_NAME, gameName)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_FOLDER_URI, folderUri)
                putExtra(EXTRA_RESOLUTION_WIDTH, resolutionWidth)
                putExtra(EXTRA_RESOLUTION_HEIGHT, resolutionHeight)
                putExtra(EXTRA_BITRATE_BITS_PER_SECOND, bitrateBitsPerSecond)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, runLengthMillis: Long) {
            context.startService(
                Intent(context, RunRecordingService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_RUN_LENGTH_MILLIS, runLengthMillis)
                }
            )
        }
    }
}

private fun buildRecordingFileName(
    gameName: String,
    category: String,
    runLengthMillis: Long,
    completedAtMillis: Long
): String {
    val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        .format(Date(completedAtMillis))
    return listOf(
        gameName.safeFileNamePart(),
        category.safeFileNamePart(),
        runLengthMillis.fileNameDuration(),
        date
    ).joinToString("_") + ".mp4"
}

private fun String.safeFileNamePart(): String {
    return trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "Unknown" }
        .take(60)
}

private fun Long.fileNameDuration(): String {
    val totalMillis = coerceAtLeast(0L)
    val hours = totalMillis / 3_600_000L
    val minutes = totalMillis / 60_000L % 60L
    val seconds = totalMillis / 1_000L % 60L
    val millis = totalMillis % 1_000L
    return String.format(Locale.US, "%02dh%02dm%02ds%03dms", hours, minutes, seconds, millis)
}
