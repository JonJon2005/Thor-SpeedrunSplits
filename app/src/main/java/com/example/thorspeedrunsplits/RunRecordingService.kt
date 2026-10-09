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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RunRecordingService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finalizationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: ScreenVideoEncoder? = null
    private var outputFile: File? = null
    private var outputDocumentUri: Uri? = null
    private var outputFileDescriptor: ParcelFileDescriptor? = null
    private var audioOutputFile: File? = null
    private var playbackAudioCapture: PlaybackAudioCapture? = null
    private var recordAudioEnabled = false
    private var saveOnlyCompletedRuns = false
    private var gameName = "Run"
    private var category = "Any%"
    private var recordingStartedAt = 0L
    private var isCleaningUp = false
    private var pendingFinalizations = 0
    private var requestedRunLengthMillis: Long? = null
    private var saveOutputOnStop = true

    private data class RecordingOutput(
        val videoFile: File?,
        val documentUri: Uri?,
        val audioFile: File?,
        val containsAudio: Boolean
    )

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
            ACTION_STOP -> scheduleStop(
                runLengthMillis = intent.getLongExtra(EXTRA_RUN_LENGTH_MILLIS, 0L),
                saveRecording = intent.getBooleanExtra(EXTRA_SAVE_RECORDING, true)
            )
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleStop(
            runLengthMillis = elapsedRecordingMillis(),
            saveRecording = !saveOnlyCompletedRuns
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(delayedStop)
        stopRecording(requestedRunLengthMillis ?: elapsedRecordingMillis())
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        mainHandler.removeCallbacks(delayedStop)
        if (videoEncoder != null || mediaProjection != null) {
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
        recordAudioEnabled = intent.getBooleanExtra(EXTRA_RECORD_AUDIO, false)
        saveOnlyCompletedRuns = intent.getBooleanExtra(EXTRA_SAVE_ONLY_COMPLETED_RUNS, false)
        requestedRunLengthMillis = null
        saveOutputOnStop = !saveOnlyCompletedRuns

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
            val frameRate = intent.getIntExtra(
                EXTRA_FRAME_RATE,
                DEFAULT_FRAME_RATE
            ).let { requestedFrameRate ->
                if (requestedFrameRate == LOWER_FRAME_RATE) {
                    LOWER_FRAME_RATE
                } else {
                    DEFAULT_FRAME_RATE
                }
            }
            val (width, height) = fitResolutionToDisplay(
                requestedWidth = resolutionWidth,
                requestedHeight = resolutionHeight,
                nativeWidth = metrics.widthPixels,
                nativeHeight = metrics.heightPixels
            )
            prepareOutput(
                folderUriString = intent.getStringExtra(EXTRA_FOLDER_URI),
                recordAudio = recordAudioEnabled
            )
            val encoder = ScreenVideoEncoder(
                outputFile = outputFile,
                outputFileDescriptor = outputFileDescriptor,
                width = width,
                height = height,
                bitrateBitsPerSecond = bitrateBitsPerSecond,
                frameRate = frameRate
            )
            videoEncoder = encoder

            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(Activity.RESULT_OK, projectionData)
                ?: error("Screen capture permission is unavailable.")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)
            encoder.start()
            virtualDisplay = projection.createVirtualDisplay(
                "ThorSpeedrunRunRecording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoder.inputSurface,
                null,
                mainHandler
            )
            recordingStartedAt = SystemClock.elapsedRealtime()
            if (recordAudioEnabled) {
                playbackAudioCapture = PlaybackAudioCapture(
                    projection = projection,
                    outputFile = requireNotNull(audioOutputFile)
                ).also { it.start(recordingStartedAt) }
            }
            isRecording = true
            broadcastRecordingState(active = true)
        } catch (_: Exception) {
            discardOutput()
            stopRecording(0L)
        }
    }

    private fun scheduleStop(runLengthMillis: Long, saveRecording: Boolean) {
        if (videoEncoder == null && mediaProjection == null) {
            stopSelf()
            return
        }
        requestedRunLengthMillis = runLengthMillis.coerceAtLeast(0L)
        saveOutputOnStop = saveRecording
        mainHandler.removeCallbacks(delayedStop)
        mainHandler.postDelayed(delayedStop, RECORDING_TAIL_MILLIS)
    }

    private fun prepareOutput(folderUriString: String?, recordAudio: Boolean) {
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
            if (recordAudio) {
                outputFile = File(cacheDir, temporaryName)
                audioOutputFile = File(
                    cacheDir,
                    temporaryName.removeSuffix(".mp4") + "_audio.m4a"
                )
            } else {
                outputFileDescriptor = contentResolver.openFileDescriptor(
                    requireNotNull(outputDocumentUri),
                    "w"
                ) ?: error("The recording file could not be opened.")
            }
        } else {
            if (recordAudio) {
                outputFile = File(cacheDir, temporaryName)
                audioOutputFile = File(
                    cacheDir,
                    temporaryName.removeSuffix(".mp4") + "_audio.m4a"
                )
            } else {
                val moviesRoot = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                val recordingsDirectory = File(moviesRoot, "Run Recordings").apply { mkdirs() }
                outputFile = File(recordingsDirectory, temporaryName)
            }
        }
    }

    private fun stopRecording(runLengthMillis: Long, stopService: Boolean = true) {
        if (isCleaningUp) return
        if (videoEncoder == null && mediaProjection == null) {
            if (pendingFinalizations > 0) return
            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        isCleaningUp = true
        mainHandler.removeCallbacks(delayedStop)

        virtualDisplay?.release()
        virtualDisplay = null
        val encoder = videoEncoder
        videoEncoder = null
        runCatching { playbackAudioCapture?.stop() }
        playbackAudioCapture = null
        val encoderStopped = runCatching { encoder?.stop() }.isSuccess
        val projection = mediaProjection
        mediaProjection = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        outputFileDescriptor?.close()
        outputFileDescriptor = null
        val output = takeOutput()
        val finalName = if (encoderStopped && saveOutputOnStop) {
            buildRecordingFileName(
                gameName = gameName,
                category = category,
                runLengthMillis = runLengthMillis,
                completedAtMillis = System.currentTimeMillis()
            )
        } else {
            null
        }
        recordingStartedAt = 0L
        requestedRunLengthMillis = null
        saveOutputOnStop = true
        isRecording = false
        broadcastRecordingState(active = false)
        isCleaningUp = false

        // Remuxing and copying can take several seconds for long runs. Keep that I/O off
        // the main thread while retaining the foreground service until it completes.
        pendingFinalizations += 1
        finalizationScope.launch {
            try {
                if (finalName != null) {
                    finalizeOutputName(output, finalName)
                } else {
                    discardOutput(output)
                }
            } catch (_: Exception) {
                discardOutput(output)
            } finally {
                mainHandler.post {
                    pendingFinalizations -= 1
                    broadcastRecordingFinalized()
                    if (
                        stopService &&
                        pendingFinalizations == 0 &&
                        videoEncoder == null &&
                        mediaProjection == null
                    ) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun finalizeOutputName(output: RecordingOutput, finalName: String) {
        if (output.containsAudio) {
            val videoFile = output.videoFile
            val audioFile = output.audioFile
            val muxedFile = if (videoFile != null && audioFile != null) {
                muxAudioIntoVideo(videoFile, audioFile)
            } else {
                null
            }
            if (muxedFile != null) {
                writeMuxedOutput(muxedFile, output.documentUri, finalName)
                muxedFile.delete()
            } else {
                discardOutput(output)
            }
            output.videoFile?.delete()
            output.audioFile?.delete()
            return
        }
        output.videoFile?.let { temporaryFile ->
            if (temporaryFile.exists()) {
                temporaryFile.renameTo(File(temporaryFile.parentFile, finalName))
            }
        }
        output.documentUri?.let { uri ->
            runCatching { DocumentsContract.renameDocument(contentResolver, uri, finalName) }
        }
    }

    private fun discardOutput() {
        runCatching { playbackAudioCapture?.stop() }
        playbackAudioCapture = null
        discardOutput(takeOutput())
    }

    private fun discardOutput(output: RecordingOutput) {
        output.videoFile?.delete()
        output.audioFile?.delete()
        output.documentUri?.let { uri ->
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
        }
    }

    private fun takeOutput(): RecordingOutput {
        val output = RecordingOutput(
            videoFile = outputFile,
            documentUri = outputDocumentUri,
            audioFile = audioOutputFile,
            containsAudio = recordAudioEnabled
        )
        outputFile = null
        outputDocumentUri = null
        outputFileDescriptor?.close()
        outputFileDescriptor = null
        audioOutputFile = null
        recordAudioEnabled = false
        return output
    }

    private fun muxAudioIntoVideo(videoFile: File, audioFile: File): File? {
        if (!videoFile.exists() || !audioFile.exists() || audioFile.length() == 0L) {
            return null
        }
        val muxedFile = File(
            cacheDir,
            videoFile.nameWithoutExtension + "_with_audio.mp4"
        )
        muxedFile.delete()
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrackIndex = findTrack(videoExtractor, "video/")
            val audioTrackIndex = findTrack(audioExtractor, "audio/")
            if (videoTrackIndex < 0 || audioTrackIndex < 0) {
                return null
            }
            val outputMuxer = MediaMuxer(
                muxedFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer = outputMuxer
            val muxedVideoTrack = outputMuxer.addTrack(
                videoExtractor.getTrackFormat(videoTrackIndex)
            )
            val muxedAudioTrack = outputMuxer.addTrack(
                audioExtractor.getTrackFormat(audioTrackIndex)
            )
            outputMuxer.start()
            muxerStarted = true
            copyExtractorSamples(
                extractor = videoExtractor,
                sourceTrackIndex = videoTrackIndex,
                muxer = outputMuxer,
                destinationTrackIndex = muxedVideoTrack
            )
            copyExtractorSamples(
                extractor = audioExtractor,
                sourceTrackIndex = audioTrackIndex,
                muxer = outputMuxer,
                destinationTrackIndex = muxedAudioTrack
            )
            outputMuxer.stop()
            muxerStarted = false
            muxedFile.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) {
            muxedFile.delete()
            null
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (trackIndex in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(trackIndex)
                .getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(mimePrefix) == true) {
                return trackIndex
            }
        }
        return -1
    }

    private fun copyExtractorSamples(
        extractor: MediaExtractor,
        sourceTrackIndex: Int,
        muxer: MediaMuxer,
        destinationTrackIndex: Int
    ) {
        extractor.selectTrack(sourceTrackIndex)
        var buffer = ByteBuffer.allocate(64 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val sampleSize = extractor.sampleSize
            if (sampleSize < 0L) break
            if (sampleSize > buffer.capacity().toLong()) {
                buffer = ByteBuffer.allocate(sampleSize.toInt())
            }
            buffer.clear()
            val bytesRead = extractor.readSampleData(buffer, 0)
            if (bytesRead < 0) break
            buffer.position(0)
            buffer.limit(bytesRead)
            bufferInfo.set(
                0,
                bytesRead,
                extractor.sampleTime.coerceAtLeast(0L),
                extractor.sampleFlags
            )
            muxer.writeSampleData(destinationTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
        extractor.unselectTrack(sourceTrackIndex)
    }

    private fun writeMuxedOutput(muxedFile: File, documentUri: Uri?, finalName: String) {
        try {
            if (documentUri != null) {
                contentResolver.openOutputStream(documentUri, "wt")?.use { output ->
                    muxedFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("The recording file could not be opened.")
                runCatching {
                    DocumentsContract.renameDocument(contentResolver, documentUri, finalName)
                }
            } else {
                val moviesRoot = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                val recordingsDirectory = File(moviesRoot, "Run Recordings").apply { mkdirs() }
                muxedFile.copyTo(File(recordingsDirectory, finalName), overwrite = true)
            }
        } catch (_: Exception) {
            documentUri?.let { uri ->
                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
            }
        }
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

    private fun broadcastRecordingFinalized() {
        sendBroadcast(
            Intent(ACTION_RECORDING_FINALIZED).apply {
                setPackage(packageName)
            }
        )
    }

    companion object {
        const val ACTION_RECORDING_STATE =
            "com.example.thorspeedrunsplits.RECORDING_STATE"
        const val ACTION_RECORDING_FINALIZED =
            "com.example.thorspeedrunsplits.RECORDING_FINALIZED"
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
        private const val EXTRA_FRAME_RATE = "frame_rate"
        private const val EXTRA_RECORD_AUDIO = "record_audio"
        private const val EXTRA_SAVE_ONLY_COMPLETED_RUNS = "save_only_completed_runs"
        private const val EXTRA_SAVE_RECORDING = "save_recording"
        private const val EXTRA_RUN_LENGTH_MILLIS = "run_length_millis"
        private const val NOTIFICATION_CHANNEL_ID = "run_recording"
        private const val NOTIFICATION_ID = 6006
        private const val RECORDING_TAIL_MILLIS = 3_000L
        private const val DEFAULT_BITRATE_BITS_PER_SECOND = 10_000_000
        private const val MIN_BITRATE_BITS_PER_SECOND = 2_000_000
        private const val MAX_BITRATE_BITS_PER_SECOND = 16_000_000
        private const val DEFAULT_FRAME_RATE = 60
        private const val LOWER_FRAME_RATE = 30

        fun start(
            context: Context,
            projectionData: Intent,
            gameName: String,
            category: String,
            folderUri: String?,
            resolutionWidth: Int,
            resolutionHeight: Int,
            bitrateBitsPerSecond: Int,
            frameRate: Int,
            recordAudio: Boolean,
            saveOnlyCompletedRuns: Boolean
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
                putExtra(EXTRA_FRAME_RATE, frameRate)
                putExtra(EXTRA_RECORD_AUDIO, recordAudio)
                putExtra(EXTRA_SAVE_ONLY_COMPLETED_RUNS, saveOnlyCompletedRuns)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(
            context: Context,
            runLengthMillis: Long,
            saveRecording: Boolean
        ) {
            context.startService(
                Intent(context, RunRecordingService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_RUN_LENGTH_MILLIS, runLengthMillis)
                    putExtra(EXTRA_SAVE_RECORDING, saveRecording)
                }
            )
        }
    }
}

private class ScreenVideoEncoder(
    outputFile: File?,
    outputFileDescriptor: ParcelFileDescriptor?,
    width: Int,
    height: Int,
    bitrateBitsPerSecond: Int,
    frameRate: Int
) {
    private val bufferInfo = MediaCodec.BufferInfo()
    private val stopRequested = AtomicBoolean(false)
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var worker: Thread? = null
    private var workerFailure: Throwable? = null
    private var started = false

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBitsPerSecond)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    val inputSurface: Surface = codec.createInputSurface()
    private val muxer = outputFileDescriptor?.let {
        MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } ?: MediaMuxer(
        requireNotNull(outputFile).absolutePath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    )

    fun start() {
        codec.start()
        started = true
        worker = Thread(::drainOutput, "ThorSpeedrunVideoEncoder").also { it.start() }
    }

    fun stop() {
        if (!started) {
            release()
            return
        }
        stopRequested.set(true)
        worker?.join(10_000L)
        if (worker?.isAlive == true) {
            release()
            error("Timed out while finalizing the video encoder.")
        }
        workerFailure?.let { failure ->
            release()
            throw failure
        }
        release()
    }

    private fun drainOutput() {
        var inputEnded = false
        try {
            while (true) {
                if (stopRequested.get() && !inputEnded) {
                    codec.signalEndOfInputStream()
                    inputEnded = true
                }
                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Video muxer format changed twice." }
                        videoTrackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                            ?: error("Video encoder output buffer is unavailable.")
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            check(muxerStarted && videoTrackIndex >= 0) {
                                "Video encoder emitted samples before its output format."
                            }
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                        val reachedEnd =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (reachedEnd) return
                    }
                }
            }
        } catch (failure: Throwable) {
            workerFailure = failure
        }
    }

    private fun release() {
        runCatching {
            if (muxerStarted) muxer.stop()
        }
        runCatching { muxer.release() }
        runCatching { inputSurface.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        started = false
    }
}

private class PlaybackAudioCapture(
    private val projection: MediaProjection,
    private val outputFile: File
) {
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var worker: Thread? = null
    private val stopRequested = AtomicBoolean(false)
    private var encoderStarted = false
    private var muxerStarted = false
    private var audioFileReady = false
    private var audioTrackIndex = -1

    fun start(recordingStartedAtElapsedRealtime: Long) {
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val sampleRate = 48_000
        val channelCount = 2
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            encoding
        )
        require(minBufferSize > 0) { "Playback audio capture is unavailable." }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setBufferSizeInBytes(minBufferSize * 2)
            .build()
        require(record.state == AudioRecord.STATE_INITIALIZED) {
            "Playback audio capture could not be initialized."
        }

        val codecFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var audioMuxer: MediaMuxer? = null
        try {
            codec.configure(codecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        audioRecord = record
        encoder = codec
        muxer = audioMuxer
        stopRequested.set(false)
        audioFileReady = false
            codec.start()
            encoderStarted = true
            record.startRecording()
            require(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback audio capture did not start."
            }
            worker = Thread(
                {
                    encodeLoop(recordingStartedAtElapsedRealtime, minBufferSize)
                },
                "ThorPlaybackAudioCapture"
            ).also { it.start() }
        } catch (exception: Exception) {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { audioMuxer?.release() }
            audioRecord = null
            encoder = null
            muxer = null
            outputFile.delete()
            throw exception
        }
    }

    fun stop() {
        stopRequested.set(true)
        runCatching { audioRecord?.stop() }
        worker?.join(3_000L)
        if (worker?.isAlive == true) {
            worker?.interrupt()
            worker?.join(500L)
        }
        worker = null
        releaseResources()
    }

    private fun encodeLoop(recordingStartedAtElapsedRealtime: Long, minBufferSize: Int) {
        val record = audioRecord ?: return
        val codec = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                audioPresentationTimeUs(recordingStartedAtElapsedRealtime),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            inputBuffer.clear()
                            val bytesRead = if (stopRequested.get()) {
                                -1
                            } else {
                                record.read(
                                    inputBuffer,
                                    minOf(inputBuffer.remaining(), minBufferSize),
                                    AudioRecord.READ_BLOCKING
                                )
                            }
                            if (bytesRead > 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    bytesRead,
                                    audioPresentationTimeUs(recordingStartedAtElapsedRealtime),
                                    0
                                )
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    audioPresentationTimeUs(recordingStartedAtElapsedRealtime),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            }
                        }
                    }
                }

                var tryAgainLater = false
                while (!tryAgainLater && !outputDone) {
                    when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> tryAgainLater = true
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Audio output format changed twice." }
                            audioTrackIndex = requireNotNull(muxer).addTrack(codec.outputFormat)
                            requireNotNull(muxer).start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (
                                outputBuffer != null &&
                                bufferInfo.size > 0 &&
                                muxerStarted &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                requireNotNull(muxer).writeSampleData(
                                    audioTrackIndex,
                                    outputBuffer,
                                    bufferInfo
                                )
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            ) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        } finally {
            releaseResources()
        }
    }

    private fun audioPresentationTimeUs(recordingStartedAtElapsedRealtime: Long): Long {
        return (SystemClock.elapsedRealtime() - recordingStartedAtElapsedRealtime)
            .coerceAtLeast(0L) * 1_000L
    }

    private fun releaseResources() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        if (encoderStarted) {
            runCatching { encoder?.stop() }
        }
        runCatching { encoder?.release() }
        encoder = null
        if (muxerStarted) {
            audioFileReady = runCatching {
                muxer?.stop()
                true
            }.getOrDefault(false)
        }
        runCatching { muxer?.release() }
        muxer = null
        if (!audioFileReady) {
            outputFile.delete()
        }
        muxerStarted = false
        encoderStarted = false
        audioTrackIndex = -1
    }
}

internal fun buildRecordingFileName(
    gameName: String,
    category: String,
    runLengthMillis: Long,
    completedAtMillis: Long
): String {
    val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        .format(Date(completedAtMillis))
    return recordingFileNamePrefix(gameName, category, runLengthMillis) + "_$date.mp4"
}

internal fun recordingFileNamePrefix(
    gameName: String,
    category: String,
    runLengthMillis: Long
): String {
    return listOf(
        gameName.safeFileNamePart(),
        category.safeFileNamePart(),
        runLengthMillis.fileNameDuration()
    ).joinToString("_")
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
