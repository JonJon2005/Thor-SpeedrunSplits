package com.example.thorspeedrunsplits

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal const val BackupFormatId = "thor-speedrun-splits-backup"
internal const val BackupSchemaVersion = 1
private const val MaxBackupBytes = 25L * 1024L * 1024L
private const val MaxBackupCharacters = 25 * 1024 * 1024
private const val MaxBackupPresetCount = 500
private const val MaxBackupSegmentCount = 2_000
private const val MaxBackupHistoryCountPerPreset = 100_000

internal data class BackupBundle(
    val createdAtMillis: Long,
    val appVersion: String,
    val activePresetName: String?,
    val presets: List<BackupPreset>
)

internal data class BackupPreset(
    val presetName: String,
    val gameTitle: String,
    val category: String,
    val segments: List<BackupPresetSegment>,
    val attemptedRuns: Int,
    val totalTimeMillis: Long,
    val personalBest: BackupPersonalBest?,
    val bestSegmentTimesMillis: List<Long?>,
    val runHistory: List<BackupHistoricalRun>
)

internal data class BackupPresetSegment(
    val name: String,
    val markerColorArgb: Int
)

internal data class BackupPersonalBest(
    val splitTimesMillis: List<Long?>,
    val finalTimeMillis: Long,
    val completedAtMillis: Long
)

internal data class BackupHistoricalRun(
    val gameTitle: String,
    val category: String,
    val finalTimeMillis: Long,
    val completedAtMillis: Long,
    val wasPersonalBest: Boolean,
    val splits: List<BackupHistoricalSplit>
)

internal data class BackupHistoricalSplit(
    val segmentName: String,
    val splitTimeMillis: Long,
    val comparisonSplitTimeMillis: Long?,
    val wasGold: Boolean
)

internal fun BackupBundle.toJsonString(): String {
    return JSONObject()
        .put("format", BackupFormatId)
        .put("schemaVersion", BackupSchemaVersion)
        .put("createdAtMillis", createdAtMillis)
        .put("appVersion", appVersion)
        .put("activePresetName", activePresetName ?: JSONObject.NULL)
        .put(
            "presets",
            JSONArray().apply {
                presets.forEach { put(it.toJsonObject()) }
            }
        )
        .toString(2)
}

internal fun backupFileName(createdAtMillis: Long): String {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        .format(Date(createdAtMillis))
    return "thor_speedrun_splits_backup_$timestamp.thorbackup.json"
}

internal fun readBackupBundleFromDocument(context: Context, documentUri: Uri): BackupBundle {
    val resolver = context.contentResolver
    val reportedSize = resolver.query(
        documentUri,
        arrayOf(OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
    require(reportedSize == null || reportedSize <= MaxBackupBytes) {
        "The selected backup is larger than 25 MB."
    }
    val inputStream = resolver.openInputStream(documentUri)
        ?: error("The selected backup file could not be opened.")
    val json = inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
        val text = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (text.length + count > MaxBackupCharacters) {
                error("The selected backup is larger than 25 MB.")
            }
            text.append(buffer, 0, count)
        }
        text.toString()
    }
    return parseBackupBundle(json)
}

internal fun parseBackupBundle(json: String): BackupBundle {
    val root = try {
        JSONObject(json)
    } catch (exception: Exception) {
        throw IllegalArgumentException("The selected file is not valid JSON.", exception)
    }
    require(root.requiredString("format") == BackupFormatId) {
        "This JSON file is not a Thor Speedrun Splits backup."
    }
    val schemaVersion = root.getInt("schemaVersion")
    require(schemaVersion == BackupSchemaVersion) {
        "Backup schema v$schemaVersion is not supported by this app version."
    }
    val presetArray = root.getJSONArray("presets")
    require(presetArray.length() in 1..MaxBackupPresetCount) {
        "The backup must contain between 1 and $MaxBackupPresetCount presets."
    }
    val presets = List(presetArray.length()) { index ->
        presetArray.getJSONObject(index).toBackupPreset(index)
    }
    require(presets.map { it.presetName }.distinct().size == presets.size) {
        "The backup contains duplicate preset names."
    }
    val activePresetName = if (root.isNull("activePresetName")) {
        null
    } else {
        root.requiredString("activePresetName")
    }
    require(activePresetName == null || presets.any { it.presetName == activePresetName }) {
        "The backup's active preset is not included in the bundle."
    }
    return BackupBundle(
        createdAtMillis = root.requiredNonNegativeLong("createdAtMillis"),
        appVersion = root.requiredString("appVersion"),
        activePresetName = activePresetName,
        presets = presets
    )
}

internal fun writeBackupBundleToTree(
    context: Context,
    treeUri: Uri,
    bundle: BackupBundle
): String {
    val resolver = context.contentResolver
    val json = bundle.toJsonString()
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
    val requestedFileName = backupFileName(bundle.createdAtMillis)
    val documentUri = DocumentsContract.createDocument(
        resolver,
        directoryUri,
        "application/json",
        requestedFileName
    ) ?: error("The selected folder did not allow creating the backup file.")

    try {
        resolver.openOutputStream(documentUri, "w")
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { writer -> writer.write(json) }
            ?: error("The backup file could not be opened for writing.")
    } catch (exception: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
        throw exception
    }

    return resolver.query(
        documentUri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: requestedFileName
}

private fun BackupPreset.toJsonObject(): JSONObject {
    return JSONObject()
        .put("presetName", presetName)
        .put("gameTitle", gameTitle)
        .put("category", category)
        .put(
            "segments",
            JSONArray().apply {
                segments.forEachIndexed { index, segment ->
                    put(
                        JSONObject()
                            .put("position", index)
                            .put("name", segment.name)
                            .put("markerColorArgb", segment.markerColorArgb)
                    )
                }
            }
        )
        .put(
            "stats",
            JSONObject()
                .put("attemptedRuns", attemptedRuns)
                .put("totalTimeMillis", totalTimeMillis)
        )
        .put("personalBest", personalBest?.toJsonObject() ?: JSONObject.NULL)
        .put("bestSegmentTimesMillis", bestSegmentTimesMillis.toNullableLongJsonArray())
        .put(
            "runHistory",
            JSONArray().apply {
                runHistory.forEach { put(it.toJsonObject()) }
            }
        )
}

private fun BackupPersonalBest.toJsonObject(): JSONObject {
    return JSONObject()
        .put("splitTimesMillis", splitTimesMillis.toNullableLongJsonArray())
        .put("finalTimeMillis", finalTimeMillis)
        .put("completedAtMillis", completedAtMillis)
}

private fun BackupHistoricalRun.toJsonObject(): JSONObject {
    return JSONObject()
        .put("gameTitle", gameTitle)
        .put("category", category)
        .put("finalTimeMillis", finalTimeMillis)
        .put("completedAtMillis", completedAtMillis)
        .put("wasPersonalBest", wasPersonalBest)
        .put(
            "splits",
            JSONArray().apply {
                splits.forEachIndexed { index, split ->
                    put(
                        JSONObject()
                            .put("position", index)
                            .put("segmentName", split.segmentName)
                            .put("splitTimeMillis", split.splitTimeMillis)
                            .put(
                                "comparisonSplitTimeMillis",
                                split.comparisonSplitTimeMillis ?: JSONObject.NULL
                            )
                            .put("wasGold", split.wasGold)
                    )
                }
            }
        )
}

private fun List<Long?>.toNullableLongJsonArray(): JSONArray {
    return JSONArray().apply {
        this@toNullableLongJsonArray.forEach { value ->
            put(value ?: JSONObject.NULL)
        }
    }
}

private fun JSONObject.toBackupPreset(presetIndex: Int): BackupPreset {
    val presetLabel = "Preset ${presetIndex + 1}"
    val segmentArray = getJSONArray("segments")
    require(segmentArray.length() in 1..MaxBackupSegmentCount) {
        "$presetLabel must contain between 1 and $MaxBackupSegmentCount splits."
    }
    val segments = List(segmentArray.length()) { index ->
        val segment = segmentArray.getJSONObject(index)
        require(segment.getInt("position") == index) {
            "$presetLabel has invalid split ordering."
        }
        BackupPresetSegment(
            name = segment.requiredString("name"),
            markerColorArgb = segment.getInt("markerColorArgb")
        )
    }
    val stats = getJSONObject("stats")
    val attemptedRunsLong = stats.requiredNonNegativeLong("attemptedRuns")
    require(attemptedRunsLong <= Int.MAX_VALUE) {
        "$presetLabel has an invalid attempt count."
    }
    val personalBest = if (isNull("personalBest")) {
        null
    } else {
        getJSONObject("personalBest").toBackupPersonalBest(
            expectedSplitCount = segments.size,
            label = "$presetLabel PB"
        )
    }
    val bestSegmentTimes = getJSONArray("bestSegmentTimesMillis")
        .toNullableLongList("$presetLabel best segments")
    require(bestSegmentTimes.size == segments.size) {
        "$presetLabel best-segment count does not match its split count."
    }
    val historyArray = getJSONArray("runHistory")
    require(historyArray.length() <= MaxBackupHistoryCountPerPreset) {
        "$presetLabel contains too many historical runs."
    }
    return BackupPreset(
        presetName = requiredString("presetName"),
        gameTitle = requiredString("gameTitle"),
        category = requiredString("category"),
        segments = segments,
        attemptedRuns = attemptedRunsLong.toInt(),
        totalTimeMillis = stats.requiredNonNegativeLong("totalTimeMillis"),
        personalBest = personalBest,
        bestSegmentTimesMillis = bestSegmentTimes,
        runHistory = List(historyArray.length()) { index ->
            historyArray.getJSONObject(index).toBackupHistoricalRun(
                label = "$presetLabel run ${index + 1}"
            )
        }
    )
}

private fun JSONObject.toBackupPersonalBest(
    expectedSplitCount: Int,
    label: String
): BackupPersonalBest {
    val splitTimes = getJSONArray("splitTimesMillis").toNullableLongList("$label splits")
    require(splitTimes.size == expectedSplitCount) {
        "$label split count does not match its preset."
    }
    validateCumulativeTimes(splitTimes, label)
    return BackupPersonalBest(
        splitTimesMillis = splitTimes,
        finalTimeMillis = requiredNonNegativeLong("finalTimeMillis"),
        completedAtMillis = requiredNonNegativeLong("completedAtMillis")
    )
}

private fun JSONObject.toBackupHistoricalRun(label: String): BackupHistoricalRun {
    val splitArray = getJSONArray("splits")
    require(splitArray.length() in 1..MaxBackupSegmentCount) {
        "$label has an invalid split count."
    }
    val splits = List(splitArray.length()) { index ->
        val split = splitArray.getJSONObject(index)
        require(split.getInt("position") == index) {
            "$label has invalid split ordering."
        }
        BackupHistoricalSplit(
            segmentName = split.requiredString("segmentName"),
            splitTimeMillis = split.requiredNonNegativeLong("splitTimeMillis"),
            comparisonSplitTimeMillis = split.optionalNonNegativeLong(
                "comparisonSplitTimeMillis"
            ),
            wasGold = split.getBoolean("wasGold")
        )
    }
    validateCumulativeTimes(splits.map { it.splitTimeMillis }, label)
    validateCumulativeTimes(splits.map { it.comparisonSplitTimeMillis }, "$label comparison")
    val finalTimeMillis = requiredNonNegativeLong("finalTimeMillis")
    require(splits.last().splitTimeMillis == finalTimeMillis) {
        "$label final time does not match its final split."
    }
    return BackupHistoricalRun(
        gameTitle = requiredString("gameTitle"),
        category = requiredString("category"),
        finalTimeMillis = finalTimeMillis,
        completedAtMillis = requiredNonNegativeLong("completedAtMillis"),
        wasPersonalBest = getBoolean("wasPersonalBest"),
        splits = splits
    )
}

private fun JSONObject.requiredString(key: String): String {
    val value = getString(key)
    require(value.isNotBlank() && value.length <= 500) {
        "Backup field '$key' is empty or too long."
    }
    return value
}

private fun JSONObject.requiredNonNegativeLong(key: String): Long {
    return getLong(key).also { value ->
        require(value >= 0L) { "Backup field '$key' cannot be negative." }
    }
}

private fun JSONObject.optionalNonNegativeLong(key: String): Long? {
    if (isNull(key)) return null
    return requiredNonNegativeLong(key)
}

private fun JSONArray.toNullableLongList(label: String): List<Long?> {
    return List(length()) { index ->
        if (isNull(index)) {
            null
        } else {
            getLong(index).also { value ->
                require(value >= 0L) { "$label contains a negative time." }
            }
        }
    }
}

private fun validateCumulativeTimes(times: List<Long?>, label: String) {
    var previousTime = 0L
    times.forEach { time ->
        if (time != null) {
            require(time >= previousTime) { "$label contains decreasing split times." }
            previousTime = time
        }
    }
}
