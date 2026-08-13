package com.example.thorspeedrunsplits

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.room.withTransaction
import com.example.thorspeedrunsplits.ui.theme.ThorSpeedrunSplitsTheme
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class SplitSegment(
    val name: String,
    val markerColor: Color
)

private data class DraftSplitSegment(
    val id: Int,
    val name: String,
    val markerColor: Color
)

private data class SplitPreset(
    val presetName: String,
    val gameTitle: String,
    val category: String,
    val segments: List<SplitSegment>
)

private data class Run(
    val presetName: String,
    val splitTimes: List<Long?>,
    val finalTime: Long,
    val completedAtMillis: Long
)

private data class HistoricalSplit(
    val segmentName: String,
    val splitTimeMillis: Long,
    val comparisonSplitTimeMillis: Long?,
    val wasGold: Boolean
)

private data class HistoricalRun(
    val id: Long,
    val presetName: String,
    val gameTitle: String,
    val category: String,
    val splits: List<HistoricalSplit>,
    val finalTimeMillis: Long,
    val completedAtMillis: Long,
    val wasPersonalBest: Boolean
)

private data class BestSegments(
    val presetName: String,
    val segmentTimes: List<Long?>
)

private data class PresetStats(
    val attemptedRuns: Int = 0,
    val totalTimeMillis: Long = 0L
)

private data class GithubRelease(
    val tagName: String,
    val htmlUrl: String
)

private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val latestVersion: String) : UpdateCheckState
    data class UpdateAvailable(val latestVersion: String, val releaseUrl: String) : UpdateCheckState
    data object Failed : UpdateCheckState
}

private sealed interface BackupExportState {
    data object Idle : BackupExportState
    data object ChoosingFolder : BackupExportState
    data object Exporting : BackupExportState
    data object Canceled : BackupExportState
    data class Success(val fileName: String, val presetCount: Int) : BackupExportState
    data class Failed(val message: String) : BackupExportState
}

private sealed interface BackupImportState {
    data object Idle : BackupImportState
    data object ChoosingFile : BackupImportState
    data object Importing : BackupImportState
    data object Canceled : BackupImportState
    data class Success(
        val presetCount: Int,
        val historyCount: Int,
        val activePresetName: String
    ) : BackupImportState
    data class Failed(val message: String) : BackupImportState
}

private data class PreparedBackupPresetImport(
    val sourcePresetName: String,
    val preset: SplitPreset,
    val stats: PresetStats,
    val personalBest: Run?,
    val bestSegments: BestSegments?,
    val existingHistory: List<HistoricalRun>,
    val historyToInsert: List<HistoricalRun>
)

private data class HistoricalRunImportKey(
    val completedAtMillis: Long,
    val finalTimeMillis: Long,
    val splits: List<Pair<String, Long>>
)

private const val LoadedPresetPreferenceKey = "loaded_preset_name"
private const val ThemePreferenceKey = "theme_mode"
private const val UseSystemThemePreferenceKey = "use_system_theme"
private const val FontPreferenceKey = "font_mode"
private const val LatestReleaseApiUrl =
    "https://api.github.com/repos/JonJon2005/Thor-SpeedrunSplits/releases/latest"
private const val UntimedSplitSentinel = -1L

private fun Run.toPersonalBestRunEntity(): PersonalBestRunEntity {
    return PersonalBestRunEntity(
        presetName = presetName,
        splitTimesMillis = splitTimes.map { it ?: UntimedSplitSentinel },
        finalTimeMillis = finalTime,
        updatedAtMillis = completedAtMillis
    )
}

private fun PersonalBestRunEntity.toRun(): Run {
    return Run(
        presetName = presetName,
        splitTimes = splitTimesMillis.map { it.takeIf { splitTime -> splitTime >= 0L } },
        finalTime = finalTimeMillis,
        completedAtMillis = updatedAtMillis
    )
}

private fun StoredCompletedRun.toHistoricalRun(): HistoricalRun {
    return HistoricalRun(
        id = run.id,
        presetName = run.presetName,
        gameTitle = run.gameTitle,
        category = run.category,
        splits = splits.sortedBy { it.position }.map { split ->
            HistoricalSplit(
                segmentName = split.segmentName,
                splitTimeMillis = split.splitTimeMillis,
                comparisonSplitTimeMillis = split.comparisonSplitTimeMillis,
                wasGold = split.wasGold
            )
        },
        finalTimeMillis = run.finalTimeMillis,
        completedAtMillis = run.completedAtMillis,
        wasPersonalBest = run.wasPersonalBest
    )
}

private fun HistoricalRun.toCompletedRunEntity(): CompletedRunEntity {
    return CompletedRunEntity(
        id = id,
        presetName = presetName,
        gameTitle = gameTitle,
        category = category,
        finalTimeMillis = finalTimeMillis,
        completedAtMillis = completedAtMillis,
        wasPersonalBest = wasPersonalBest
    )
}

private fun HistoricalRun.toCompletedRunSplitEntities(): List<CompletedRunSplitEntity> {
    return splits.mapIndexed { index, split ->
        CompletedRunSplitEntity(
            runId = id,
            position = index,
            segmentName = split.segmentName,
            splitTimeMillis = split.splitTimeMillis,
            comparisonSplitTimeMillis = split.comparisonSplitTimeMillis,
            wasGold = split.wasGold
        )
    }
}

private fun createBackupBundle(
    selectedPresetNames: Set<String>,
    activePresetName: String,
    savedPresets: List<SplitPreset>,
    savedRuns: Map<String, Run>,
    savedBestSegments: Map<String, BestSegments>,
    completedRunHistory: Map<String, List<HistoricalRun>>,
    presetStats: Map<String, PresetStats>,
    createdAtMillis: Long = System.currentTimeMillis()
): BackupBundle {
    val selectedPresets = savedPresets.filter { it.presetName in selectedPresetNames }
    return BackupBundle(
        createdAtMillis = createdAtMillis,
        appVersion = BuildConfig.VERSION_NAME,
        activePresetName = activePresetName.takeIf { it in selectedPresetNames }
            ?: selectedPresets.firstOrNull()?.presetName,
        presets = selectedPresets.map { preset ->
            val stats = presetStats[preset.presetName] ?: PresetStats()
            val personalBest = savedRuns[preset.presetName]?.let { run ->
                BackupPersonalBest(
                    splitTimesMillis = run.splitTimes,
                    finalTimeMillis = run.finalTime,
                    completedAtMillis = run.completedAtMillis
                )
            }
            val bestSegmentTimes = List(preset.segments.size) { index ->
                savedBestSegments[preset.presetName]?.segmentTimes?.getOrNull(index)
            }
            BackupPreset(
                presetName = preset.presetName,
                gameTitle = preset.gameTitle,
                category = preset.category,
                segments = preset.segments.map { segment ->
                    BackupPresetSegment(
                        name = segment.name,
                        markerColorArgb = segment.markerColor.toArgb()
                    )
                },
                attemptedRuns = stats.attemptedRuns,
                totalTimeMillis = stats.totalTimeMillis,
                personalBest = personalBest,
                bestSegmentTimesMillis = bestSegmentTimes,
                runHistory = completedRunHistory[preset.presetName].orEmpty().map { run ->
                    BackupHistoricalRun(
                        gameTitle = run.gameTitle,
                        category = run.category,
                        finalTimeMillis = run.finalTimeMillis,
                        completedAtMillis = run.completedAtMillis,
                        wasPersonalBest = run.wasPersonalBest,
                        splits = run.splits.map { split ->
                            BackupHistoricalSplit(
                                segmentName = split.segmentName,
                                splitTimeMillis = split.splitTimeMillis,
                                comparisonSplitTimeMillis = split.comparisonSplitTimeMillis,
                                wasGold = split.wasGold
                            )
                        }
                    )
                }
            )
        }
    )
}

private fun prepareBackupImport(
    bundle: BackupBundle,
    savedPresets: List<SplitPreset>,
    savedRuns: Map<String, Run>,
    savedBestSegments: Map<String, BestSegments>,
    completedRunHistory: Map<String, List<HistoricalRun>>,
    presetStats: Map<String, PresetStats>
): List<PreparedBackupPresetImport> {
    val knownPresets = savedPresets.associateBy { it.presetName }.toMutableMap()
    return bundle.presets.map { backupPreset ->
        val sourcePreset = backupPreset.toSplitPreset()
        val targetName = resolveImportedPresetName(sourcePreset, knownPresets)
        val targetPreset = sourcePreset.copy(presetName = targetName)
        knownPresets[targetName] = targetPreset

        val existingStats = presetStats[targetName] ?: PresetStats()
        val importedStats = PresetStats(
            attemptedRuns = backupPreset.attemptedRuns,
            totalTimeMillis = backupPreset.totalTimeMillis
        )
        val importedPersonalBest = backupPreset.personalBest?.toRun(targetName)
        val mergedPersonalBest = betterPersonalBest(
            first = savedRuns[targetName]?.takeIf {
                it.splitTimes.size == targetPreset.segments.size
            },
            second = importedPersonalBest
        )
        val importedBestSegments = BestSegments(
            presetName = targetName,
            segmentTimes = backupPreset.bestSegmentTimesMillis
        ).takeIf { bestSegments -> bestSegments.segmentTimes.any { it != null } }
        val mergedBestSegments = mergeBestSegments(
            presetName = targetName,
            segmentCount = targetPreset.segments.size,
            first = savedBestSegments[targetName]?.takeIf {
                it.segmentTimes.size == targetPreset.segments.size
            },
            second = importedBestSegments
        )
        val existingHistory = completedRunHistory[targetName].orEmpty()
        val existingHistoryKeys = existingHistory.mapTo(mutableSetOf()) { it.importKey() }
        val importedHistory = backupPreset.runHistory.map { it.toHistoricalRun(targetName) }
        val historyToInsert = importedHistory.filter { history ->
            existingHistoryKeys.add(history.importKey())
        }

        PreparedBackupPresetImport(
            sourcePresetName = backupPreset.presetName,
            preset = targetPreset,
            stats = PresetStats(
                attemptedRuns = maxOf(existingStats.attemptedRuns, importedStats.attemptedRuns),
                totalTimeMillis = maxOf(
                    existingStats.totalTimeMillis,
                    importedStats.totalTimeMillis
                )
            ),
            personalBest = mergedPersonalBest,
            bestSegments = mergedBestSegments,
            existingHistory = existingHistory,
            historyToInsert = historyToInsert
        )
    }
}

private fun BackupPreset.toSplitPreset(): SplitPreset {
    return SplitPreset(
        presetName = presetName,
        gameTitle = gameTitle,
        category = category,
        segments = segments.map { segment ->
            SplitSegment(
                name = segment.name,
                markerColor = Color(segment.markerColorArgb)
            )
        }
    )
}

private fun resolveImportedPresetName(
    importedPreset: SplitPreset,
    knownPresets: Map<String, SplitPreset>
): String {
    val existingPreset = knownPresets[importedPreset.presetName]
        ?: return importedPreset.presetName
    if (existingPreset.hasSameDefinitionAs(importedPreset)) {
        return importedPreset.presetName
    }
    var suffix = 1
    while (true) {
        val candidate = if (suffix == 1) {
            "${importedPreset.presetName} (Imported)"
        } else {
            "${importedPreset.presetName} (Imported $suffix)"
        }
        val candidatePreset = knownPresets[candidate]
        if (candidatePreset == null || candidatePreset.hasSameDefinitionAs(importedPreset)) {
            return candidate
        }
        suffix += 1
    }
}

private fun SplitPreset.hasSameDefinitionAs(other: SplitPreset): Boolean {
    return gameTitle == other.gameTitle &&
        category == other.category &&
        segments == other.segments
}

private fun BackupPersonalBest.toRun(presetName: String): Run {
    return Run(
        presetName = presetName,
        splitTimes = splitTimesMillis,
        finalTime = finalTimeMillis,
        completedAtMillis = completedAtMillis
    )
}

private fun BackupHistoricalRun.toHistoricalRun(presetName: String): HistoricalRun {
    return HistoricalRun(
        id = 0L,
        presetName = presetName,
        gameTitle = gameTitle,
        category = category,
        splits = splits.map { split ->
            HistoricalSplit(
                segmentName = split.segmentName,
                splitTimeMillis = split.splitTimeMillis,
                comparisonSplitTimeMillis = split.comparisonSplitTimeMillis,
                wasGold = split.wasGold
            )
        },
        finalTimeMillis = finalTimeMillis,
        completedAtMillis = completedAtMillis,
        wasPersonalBest = wasPersonalBest
    )
}

private fun betterPersonalBest(first: Run?, second: Run?): Run? {
    if (first == null) return second
    if (second == null) return first
    val firstTimedSplitCount = first.splitTimes.count { it != null }
    val secondTimedSplitCount = second.splitTimes.count { it != null }
    return when {
        secondTimedSplitCount > firstTimedSplitCount -> second
        secondTimedSplitCount < firstTimedSplitCount -> first
        second.finalTime < first.finalTime -> second
        else -> first
    }
}

private fun mergeBestSegments(
    presetName: String,
    segmentCount: Int,
    first: BestSegments?,
    second: BestSegments?
): BestSegments? {
    val mergedTimes = List(segmentCount) { index ->
        listOfNotNull(
            first?.segmentTimes?.getOrNull(index),
            second?.segmentTimes?.getOrNull(index)
        ).minOrNull()
    }
    return BestSegments(presetName, mergedTimes).takeIf { mergedTimes.any { it != null } }
}

private fun HistoricalRun.importKey(): HistoricalRunImportKey {
    return HistoricalRunImportKey(
        completedAtMillis = completedAtMillis,
        finalTimeMillis = finalTimeMillis,
        splits = splits.map { it.segmentName to it.splitTimeMillis }
    )
}

private fun BestSegments.toBestSegmentsEntity(): BestSegmentsEntity {
    return BestSegmentsEntity(
        presetName = presetName,
        segmentTimesMillis = segmentTimes.map { it ?: UntimedSplitSentinel },
        updatedAtMillis = System.currentTimeMillis()
    )
}

private fun BestSegmentsEntity.toBestSegments(): BestSegments {
    return BestSegments(
        presetName = presetName,
        segmentTimes = segmentTimesMillis.map { it.takeIf { segmentTime -> segmentTime >= 0L } }
    )
}

private fun migrateRunForEditedPreset(
    oldPreset: SplitPreset,
    editedPreset: SplitPreset,
    run: Run
): Run? {
    if (
        oldPreset.segments.isEmpty() ||
        editedPreset.segments.isEmpty() ||
        run.splitTimes.size != oldPreset.segments.size
    ) {
        return null
    }

    val oldFirst = oldPreset.segments.first().name
    val oldLast = oldPreset.segments.last().name
    val editedFirst = editedPreset.segments.first().name
    val editedLast = editedPreset.segments.last().name
    if (oldFirst != editedFirst || oldLast != editedLast) {
        return null
    }

    val usedOldIndices = BooleanArray(oldPreset.segments.size)
    val migratedSplitTimes = editedPreset.segments.mapIndexed { index, segment ->
        when (index) {
            0 -> {
                usedOldIndices[0] = true
                run.splitTimes.firstOrNull()
            }
            editedPreset.segments.lastIndex -> {
                val oldLastIndex = oldPreset.segments.lastIndex
                usedOldIndices[oldLastIndex] = true
                run.splitTimes.getOrNull(oldLastIndex)
            }
            else -> {
                val oldIndex = oldPreset.segments.indices.firstOrNull { oldIndex ->
                    !usedOldIndices[oldIndex] && oldPreset.segments[oldIndex].name == segment.name
                }
                if (oldIndex != null) {
                    usedOldIndices[oldIndex] = true
                    run.splitTimes.getOrNull(oldIndex)
                } else {
                    null
                }
            }
        }
    }

    return run.copy(splitTimes = migratedSplitTimes)
}

private fun migrateBestSegmentsForEditedPreset(
    oldPreset: SplitPreset,
    editedPreset: SplitPreset,
    bestSegments: BestSegments
): BestSegments? {
    if (
        oldPreset.segments.isEmpty() ||
        editedPreset.segments.isEmpty() ||
        bestSegments.segmentTimes.size != oldPreset.segments.size
    ) {
        return null
    }

    val oldSegmentKeys = oldPreset.segments.indices.associateBy { index ->
        segmentKey(oldPreset, index)
    }
    val migratedSegmentTimes = editedPreset.segments.indices.map { index ->
        oldSegmentKeys[segmentKey(editedPreset, index)]?.let { oldIndex ->
            bestSegments.segmentTimes.getOrNull(oldIndex)
        }
    }
    if (migratedSegmentTimes.all { it == null }) {
        return null
    }
    return bestSegments.copy(segmentTimes = migratedSegmentTimes)
}

private fun segmentKey(preset: SplitPreset, index: Int): String {
    val previousName = if (index == 0) {
        "START"
    } else {
        preset.segments[index - 1].name
    }
    return "$previousName->${preset.segments[index].name}"
}

private fun SplitPreset.toSplitPresetEntity(stats: PresetStats = PresetStats()): SplitPresetEntity {
    return SplitPresetEntity(
        presetName = presetName,
        gameTitle = gameTitle,
        category = category,
        attemptedRuns = stats.attemptedRuns,
        totalTimeMillis = stats.totalTimeMillis,
        updatedAtMillis = System.currentTimeMillis()
    )
}

private fun SplitPreset.toSplitPresetSegmentEntities(): List<SplitPresetSegmentEntity> {
    return segments.mapIndexed { index, segment ->
        SplitPresetSegmentEntity(
            presetName = presetName,
            position = index,
            name = segment.name,
            markerColorArgb = segment.markerColor.toArgb()
        )
    }
}

private fun StoredSplitPreset.toSplitPreset(): SplitPreset? {
    if (segments.isEmpty()) {
        return null
    }
    return SplitPreset(
        presetName = preset.presetName,
        gameTitle = preset.gameTitle,
        category = preset.category,
        segments = segments.sortedBy { it.position }.map { segment ->
            SplitSegment(
                name = segment.name,
                markerColor = Color(segment.markerColorArgb)
            )
        }
    )
}

private fun SplitPresetEntity.toPresetStats(): PresetStats {
    return PresetStats(
        attemptedRuns = attemptedRuns,
        totalTimeMillis = totalTimeMillis
    )
}

private val PresetColors = listOf(
    Color(0xFFFF4040),
    Color(0xFFFF7A2F),
    Color(0xFFFFD33D),
    Color(0xFF65E36F),
    Color(0xFF24D8A8),
    Color(0xFF37D5FF),
    Color(0xFF3B70FF),
    Color(0xFF8F5CFF),
    Color(0xFFFF4FA3),
    Color(0xFFF6F6F6)
)

private fun nextPresetColor(currentColor: Color): Color {
    val currentIndex = PresetColors.indexOf(currentColor)
    return if (currentIndex >= 0) {
        PresetColors[(currentIndex + 1) % PresetColors.size]
    } else {
        PresetColors.first()
    }
}

private val DefaultPreset = SplitPreset(
    presetName = "Default Example",
    gameTitle = "Game",
    category = "Any%",
    segments = listOf(
        SplitSegment("One", PresetColors[0]),
        SplitSegment("Two", PresetColors[1]),
        SplitSegment("Three", PresetColors[2]),
        SplitSegment("Four", PresetColors[3]),
        SplitSegment("Five", PresetColors[4]),
        SplitSegment("Six", PresetColors[5]),
        SplitSegment("Seven", PresetColors[6]),
        SplitSegment("Eight", PresetColors[7]),
        SplitSegment("Nine", PresetColors[8]),
        SplitSegment("Ten", PresetColors[9])
    )
)

private val GoldSplit = Color(0xFFFFD84D)
private val LinkBlue = Color(0xFF5EA1FF)
private const val ButtonFadeMillis = 280
private const val ButtonVibrationMillis = 18L
private const val ButtonVibrationAmplitude = 36

private data class AppThemeColors(
    val screenBackground: Color,
    val rowBackground: Color,
    val activeRowBackground: Color,
    val divider: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val successGreen: Color,
    val behindRed: Color,
    val liveActiveSuccessGreen: Color,
    val liveActiveBehindRed: Color
)

private enum class AppThemeMode(
    val storageValue: String,
    val label: String
) {
    Light("light", "LIGHT"),
    Dark("dark", "DARK"),
    Oled("oled", "OLED");

    companion object {
        fun fromStorageValue(value: String?): AppThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: Oled
        }
    }
}

private enum class AppFontMode(
    val storageValue: String,
    val label: String,
    val fontFamily: FontFamily
) {
    Default("default", "DEFAULT", FontFamily.Default),
    Pixel("pixel", "PIXEL", FontFamily(Font(R.font.pixel))),
    PixelBold("pixel_bold", "PIXEL BOLD", FontFamily(Font(R.font.pixel_bold))),
    PrincessLegend(
        "princess_legend",
        "PRINCESS",
        FontFamily(Font(R.font.princess_legend))
    ),
    JustBreathe("just_breathe", "BREATHE", FontFamily(Font(R.font.just_breathe))),
    RedHatMan("red_hat_man", "RED HAT", FontFamily(Font(R.font.red_hat_man)));

    companion object {
        fun fromStorageValue(value: String?): AppFontMode {
            return entries.firstOrNull { it.storageValue == value } ?: Default
        }
    }
}

private val OledThemeColors = AppThemeColors(
    screenBackground = Color(0xFF020202),
    rowBackground = Color(0xFF080808),
    activeRowBackground = Color(0xFF111111),
    divider = Color(0xFF242424),
    primaryText = Color(0xFFF6F6F6),
    secondaryText = Color(0xFFC8C8C8),
    successGreen = Color(0xFF65E38F),
    behindRed = Color(0xFFFF7070),
    liveActiveSuccessGreen = Color(0xFF00FF66),
    liveActiveBehindRed = Color(0xFFFF3333)
)

private val DarkThemeColors = AppThemeColors(
    screenBackground = Color(0xFF141414),
    rowBackground = Color(0xFF1C1C1C),
    activeRowBackground = Color(0xFF252525),
    divider = Color(0xFF3A3A3A),
    primaryText = Color(0xFFF3F3F3),
    secondaryText = Color(0xFFC9C9C9),
    successGreen = Color(0xFF65E38F),
    behindRed = Color(0xFFFF7070),
    liveActiveSuccessGreen = Color(0xFF00FF66),
    liveActiveBehindRed = Color(0xFFFF3333)
)

private val LightThemeColors = AppThemeColors(
    screenBackground = Color(0xFFF4F4F1),
    rowBackground = Color(0xFFFFFFFF),
    activeRowBackground = Color(0xFFEAEFEB),
    divider = Color(0xFFC8C8C2),
    primaryText = Color(0xFF151515),
    secondaryText = Color(0xFF575757),
    successGreen = Color(0xFF148A3D),
    behindRed = Color(0xFFD42121),
    liveActiveSuccessGreen = Color(0xFF008F2F),
    liveActiveBehindRed = Color(0xFFE00000)
)

private val LocalAppThemeColors = staticCompositionLocalOf { OledThemeColors }
private val LocalAppFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

private fun colorsForTheme(themeMode: AppThemeMode): AppThemeColors {
    return when (themeMode) {
        AppThemeMode.Light -> LightThemeColors
        AppThemeMode.Dark -> DarkThemeColors
        AppThemeMode.Oled -> OledThemeColors
    }
}

private val OledBlack: Color
    @Composable get() = LocalAppThemeColors.current.screenBackground
private val RowBlack: Color
    @Composable get() = LocalAppThemeColors.current.rowBackground
private val ActiveRowBackground: Color
    @Composable get() = LocalAppThemeColors.current.activeRowBackground
private val DividerColor: Color
    @Composable get() = LocalAppThemeColors.current.divider
private val PrimaryText: Color
    @Composable get() = LocalAppThemeColors.current.primaryText
private val SecondaryText: Color
    @Composable get() = LocalAppThemeColors.current.secondaryText
private val SuccessGreen: Color
    @Composable get() = LocalAppThemeColors.current.successGreen
private val BehindRed: Color
    @Composable get() = LocalAppThemeColors.current.behindRed
private val LiveActiveSuccessGreen: Color
    @Composable get() = LocalAppThemeColors.current.liveActiveSuccessGreen
private val LiveActiveBehindRed: Color
    @Composable get() = LocalAppThemeColors.current.liveActiveBehindRed

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            ThorSpeedrunSplitsTheme(dynamicColor = false, darkTheme = true) {
                ThorSpeedrunSplitsApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun ThorSpeedrunSplitsApp() {
    val appContext = LocalContext.current.applicationContext
    val database = remember {
        ThorSpeedrunDatabase.getInstance(appContext)
    }
    val personalBestRunDao = remember(database) { database.personalBestRunDao() }
    val bestSegmentsDao = remember(database) { database.bestSegmentsDao() }
    val completedRunDao = remember(database) { database.completedRunDao() }
    val splitPresetDao = remember(database) { database.splitPresetDao() }
    val appPreferenceDao = remember(database) { database.appPreferenceDao() }
    val coroutineScope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }
    var activeSplitIndex by remember { mutableStateOf(0) }
    var resetScrollRequest by remember { mutableStateOf(0) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var activePreset by remember { mutableStateOf(DefaultPreset) }
    var selectedThemeMode by remember { mutableStateOf(AppThemeMode.Oled) }
    var useSystemTheme by remember { mutableStateOf(false) }
    var selectedFontMode by remember { mutableStateOf(AppFontMode.Default) }
    var updateCheckState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var presetSettingsTab by remember { mutableStateOf(PresetSettingsTab.Create) }
    var editPresetScrollRequest by remember { mutableStateOf(0) }
    var presetPendingDelete by remember { mutableStateOf<SplitPreset?>(null) }
    val savedPresets = remember {
        mutableStateListOf<SplitPreset>().apply { add(DefaultPreset) }
    }
    val savedRuns = remember { mutableStateMapOf<String, Run>() }
    val savedBestSegments = remember { mutableStateMapOf<String, BestSegments>() }
    val completedRunHistory = remember { mutableStateMapOf<String, List<HistoricalRun>>() }
    val presetStats = remember { mutableStateMapOf<String, PresetStats>() }
    var draftPresetName by remember { mutableStateOf("New Preset") }
    var draftGameTitle by remember { mutableStateOf(DefaultPreset.gameTitle) }
    var draftCategory by remember { mutableStateOf(DefaultPreset.category) }
    var nextDraftSegmentId by remember { mutableStateOf(DefaultPreset.segments.size) }
    val draftSegments = remember {
        mutableStateListOf<DraftSplitSegment>().apply {
            addAll(
                DefaultPreset.segments.take(4).mapIndexed { index, segment ->
                    DraftSplitSegment(
                        id = index,
                        name = segment.name,
                        markerColor = segment.markerColor
                    )
                }
            )
        }
    }
    var editTargetPresetName by remember { mutableStateOf<String?>(null) }
    var editGameTitle by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf("") }
    var nextEditSegmentId by remember { mutableStateOf(0) }
    val editSegments = remember { mutableStateListOf<DraftSplitSegment>() }
    var startedAtMillis by remember { mutableLongStateOf(0L) }
    var finishedElapsedMillis by remember { mutableLongStateOf(0L) }
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var persistedCurrentRunMillis by remember { mutableLongStateOf(0L) }
    var runComparison by remember { mutableStateOf<Run?>(null) }
    val completedTimes = remember {
        mutableStateListOf<Long?>().apply {
            repeat(DefaultPreset.segments.size) { add(null) }
        }
    }
    val goldSplitIndices = remember { mutableStateListOf<Int>() }
    val bestSegmentRollbackValues = remember { mutableStateMapOf<Int, Long>() }
    var backupExportState by remember { mutableStateOf<BackupExportState>(BackupExportState.Idle) }
    var backupImportState by remember { mutableStateOf<BackupImportState>(BackupImportState.Idle) }
    var pendingBackupBundle by remember { mutableStateOf<BackupBundle?>(null) }
    val backupFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { selectedFolderUri ->
        val bundle = pendingBackupBundle
        if (selectedFolderUri == null || bundle == null) {
            backupExportState = BackupExportState.Canceled
            pendingBackupBundle = null
        } else {
            backupExportState = BackupExportState.Exporting
            coroutineScope.launch {
                backupExportState = try {
                    val fileName = withContext(Dispatchers.IO) {
                        writeBackupBundleToTree(
                            context = appContext,
                            treeUri = selectedFolderUri,
                            bundle = bundle
                        )
                    }
                    BackupExportState.Success(
                        fileName = fileName,
                        presetCount = bundle.presets.size
                    )
                } catch (exception: Exception) {
                    BackupExportState.Failed(
                        message = exception.message ?: "The backup could not be written."
                    )
                }
                pendingBackupBundle = null
            }
        }
    }

    fun resetRun(segmentCount: Int) {
        isRunning = false
        isFinished = false
        activeSplitIndex = 0
        startedAtMillis = 0L
        finishedElapsedMillis = 0L
        nowMillis = SystemClock.elapsedRealtime()
        persistedCurrentRunMillis = 0L
        runComparison = null
        completedTimes.clear()
        repeat(segmentCount) { completedTimes.add(null) }
        goldSplitIndices.clear()
        bestSegmentRollbackValues.clear()
        resetScrollRequest += 1
    }

    fun loadPreset(preset: SplitPreset) {
        activePreset = preset
        resetRun(preset.segments.size)
        isSettingsOpen = false
        coroutineScope.launch {
            appPreferenceDao.upsert(
                AppPreferenceEntity(
                    key = LoadedPresetPreferenceKey,
                    value = preset.presetName
                )
            )
        }
    }

    fun deletePreset(preset: SplitPreset) {
        if (preset.presetName == DefaultPreset.presetName) {
            return
        }

        val deletedActivePreset = preset.presetName == activePreset.presetName
        val deleteIndex = savedPresets.indexOfFirst {
            it.presetName == preset.presetName
        }
        if (deleteIndex >= 0) {
            savedPresets.removeAt(deleteIndex)
            savedRuns.remove(preset.presetName)
            savedBestSegments.remove(preset.presetName)
            presetStats.remove(preset.presetName)
            coroutineScope.launch {
                personalBestRunDao.deleteByPresetName(preset.presetName)
                bestSegmentsDao.deleteByPresetName(preset.presetName)
                completedRunDao.deleteByPresetName(preset.presetName)
                splitPresetDao.deleteByPresetName(preset.presetName)
            }
            completedRunHistory.remove(preset.presetName)
            if (editTargetPresetName == preset.presetName) {
                editTargetPresetName = null
                editSegments.clear()
            }
        }
        if (deletedActivePreset) {
            loadPreset(DefaultPreset)
        }
    }

    fun startEditingPreset(preset: SplitPreset) {
        editTargetPresetName = preset.presetName
        editGameTitle = preset.gameTitle
        editCategory = preset.category
        editSegments.clear()
        editSegments.addAll(
            preset.segments.mapIndexed { index, segment ->
                DraftSplitSegment(
                    id = index,
                    name = segment.name,
                    markerColor = segment.markerColor
                )
            }
        )
        nextEditSegmentId = preset.segments.size
    }

    fun openReleasePage(url: String) {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun checkForAppUpdate() {
        updateCheckState = UpdateCheckState.Checking
        coroutineScope.launch {
            updateCheckState = try {
                val latestRelease = fetchLatestGithubRelease()
                if (isRemoteVersionNewer(BuildConfig.VERSION_NAME, latestRelease.tagName)) {
                    UpdateCheckState.UpdateAvailable(
                        latestVersion = latestRelease.tagName,
                        releaseUrl = latestRelease.htmlUrl
                    )
                } else {
                    UpdateCheckState.UpToDate(latestRelease.tagName)
                }
            } catch (_: Exception) {
                UpdateCheckState.Failed
            }
        }
    }

    val backupFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedBackupUri ->
        if (selectedBackupUri == null) {
            backupImportState = BackupImportState.Canceled
        } else {
            backupImportState = BackupImportState.Importing
            coroutineScope.launch {
                try {
                    val bundle = withContext(Dispatchers.IO) {
                        readBackupBundleFromDocument(appContext, selectedBackupUri)
                    }
                    val preparedImports = prepareBackupImport(
                        bundle = bundle,
                        savedPresets = savedPresets,
                        savedRuns = savedRuns,
                        savedBestSegments = savedBestSegments,
                        completedRunHistory = completedRunHistory,
                        presetStats = presetStats
                    )
                    val importedActivePreset = preparedImports.firstOrNull { preparedImport ->
                        preparedImport.sourcePresetName == bundle.activePresetName
                    }?.preset ?: preparedImports.first().preset
                    val insertedHistoryByPreset = mutableMapOf<String, List<HistoricalRun>>()
                    database.withTransaction {
                        preparedImports.forEach { preparedImport ->
                            val preset = preparedImport.preset
                            splitPresetDao.upsertWithSegments(
                                preset = preset.toSplitPresetEntity(preparedImport.stats),
                                segments = preset.toSplitPresetSegmentEntities()
                            )
                            preparedImport.personalBest?.let { personalBest ->
                                personalBestRunDao.upsert(personalBest.toPersonalBestRunEntity())
                            }
                            preparedImport.bestSegments?.let { bestSegments ->
                                bestSegmentsDao.upsert(bestSegments.toBestSegmentsEntity())
                            }
                            insertedHistoryByPreset[preset.presetName] =
                                preparedImport.historyToInsert.map { historicalRun ->
                                    val runId = completedRunDao.insertWithSplits(
                                        run = historicalRun.toCompletedRunEntity(),
                                        splits = historicalRun.toCompletedRunSplitEntities()
                                    )
                                    historicalRun.copy(id = runId)
                                }
                        }
                        appPreferenceDao.upsert(
                            AppPreferenceEntity(
                                key = LoadedPresetPreferenceKey,
                                value = importedActivePreset.presetName
                            )
                        )
                    }

                    preparedImports.forEach { preparedImport ->
                        val preset = preparedImport.preset
                        val existingIndex = savedPresets.indexOfFirst {
                            it.presetName == preset.presetName
                        }
                        if (existingIndex >= 0) {
                            savedPresets[existingIndex] = preset
                        } else {
                            savedPresets.add(preset)
                        }
                        presetStats[preset.presetName] = preparedImport.stats
                        preparedImport.personalBest?.let {
                            savedRuns[preset.presetName] = it
                        }
                        preparedImport.bestSegments?.let {
                            savedBestSegments[preset.presetName] = it
                        }
                        completedRunHistory[preset.presetName] = (
                            preparedImport.existingHistory +
                                insertedHistoryByPreset[preset.presetName].orEmpty()
                            ).sortedByDescending { it.completedAtMillis }
                    }

                    activePreset = importedActivePreset
                    resetRun(importedActivePreset.segments.size)
                    isSettingsOpen = false
                    backupImportState = BackupImportState.Success(
                        presetCount = preparedImports.size,
                        historyCount = insertedHistoryByPreset.values.sumOf { it.size },
                        activePresetName = importedActivePreset.presetName
                    )
                } catch (exception: Exception) {
                    backupImportState = BackupImportState.Failed(
                        message = exception.message ?: "The backup could not be imported."
                    )
                }
            }
        }
    }

    LaunchedEffect(
        personalBestRunDao,
        bestSegmentsDao,
        completedRunDao,
        splitPresetDao,
        appPreferenceDao
    ) {
        checkForAppUpdate()

        splitPresetDao.ensurePresetExists(
            preset = DefaultPreset.toSplitPresetEntity(),
            segments = DefaultPreset.toSplitPresetSegmentEntities()
        )

        savedRuns.clear()
        personalBestRunDao.getAll().forEach { savedRun ->
            savedRuns[savedRun.presetName] = savedRun.toRun()
        }

        savedBestSegments.clear()
        bestSegmentsDao.getAll().forEach { savedBestSegment ->
            savedBestSegments[savedBestSegment.presetName] = savedBestSegment.toBestSegments()
        }

        completedRunHistory.clear()
        completedRunDao.getAllWithSplits()
            .map { it.toHistoricalRun() }
            .groupBy { it.presetName }
            .forEach { (presetName, runs) ->
                completedRunHistory[presetName] = runs.sortedByDescending {
                    it.completedAtMillis
                }
            }

        val storedPresetRows = splitPresetDao.getAllWithSegments()
        presetStats.clear()
        storedPresetRows.forEach { storedPreset ->
            presetStats[storedPreset.preset.presetName] = storedPreset.preset.toPresetStats()
        }
        val storedPresets = storedPresetRows
            .mapNotNull { it.toSplitPreset() }
            .filter { it.presetName != DefaultPreset.presetName }
        savedPresets.clear()
        savedPresets.add(DefaultPreset)
        savedPresets.addAll(storedPresets)

        val loadedPresetName = appPreferenceDao.getValue(LoadedPresetPreferenceKey)
        val restoredPreset = savedPresets.firstOrNull {
            it.presetName == loadedPresetName
        } ?: DefaultPreset
        selectedThemeMode = AppThemeMode.fromStorageValue(
            appPreferenceDao.getValue(ThemePreferenceKey)
        )
        useSystemTheme = appPreferenceDao.getValue(UseSystemThemePreferenceKey) == "true"
        selectedFontMode = AppFontMode.fromStorageValue(
            appPreferenceDao.getValue(FontPreferenceKey)
        )
        activePreset = restoredPreset
        resetRun(restoredPreset.segments.size)
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            val currentTimeMillis = SystemClock.elapsedRealtime()
            nowMillis = currentTimeMillis
            val elapsedThisRun = currentTimeMillis - startedAtMillis
            val elapsedSinceLastPersist = elapsedThisRun - persistedCurrentRunMillis
            if (elapsedSinceLastPersist >= 1000L) {
                val presetName = activePreset.presetName
                val currentStats = presetStats[presetName] ?: PresetStats()
                presetStats[presetName] = currentStats.copy(
                    totalTimeMillis = currentStats.totalTimeMillis + elapsedSinceLastPersist
                )
                persistedCurrentRunMillis = elapsedThisRun
                launch {
                    splitPresetDao.addTotalTime(presetName, elapsedSinceLastPersist)
                }
            }
            delay(33L)
        }
    }

    val systemThemeMode = if (isSystemInDarkTheme()) {
        AppThemeMode.Dark
    } else {
        AppThemeMode.Light
    }
    val effectiveThemeMode = if (useSystemTheme) {
        systemThemeMode
    } else {
        selectedThemeMode
    }

    val selectedFontFamily = selectedFontMode.fontFamily
    val defaultTextStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalAppThemeColors provides colorsForTheme(effectiveThemeMode),
        LocalAppFontFamily provides selectedFontFamily,
        LocalTextStyle provides defaultTextStyle.copy(fontFamily = selectedFontFamily)
    ) {
    val elapsedMillis = when {
        isRunning -> nowMillis - startedAtMillis
        isFinished -> finishedElapsedMillis
        else -> 0L
    }
    val savedRunForActivePreset = savedRuns[activePreset.presetName]
        ?.takeIf { it.splitTimes.size == activePreset.segments.size }
    val displayedComparisonRun = runComparison ?: savedRunForActivePreset
    val activeBestSegments = savedBestSegments[activePreset.presetName]
        ?.takeIf { it.segmentTimes.size == activePreset.segments.size }
    val sumOfBestText = activeBestSegments
        ?.segmentTimes
        ?.takeIf { segmentTimes ->
            segmentTimes.size == activePreset.segments.size && segmentTimes.all { it != null }
        }
        ?.sumOf { it ?: 0L }
        ?.let(::formatDuration)
    val latestCompletedSplitDeltaMillis = if (isRunning || isFinished) {
        completedTimes.indices.reversed().firstNotNullOfOrNull { index ->
            val completedTime = completedTimes.getOrNull(index)
            val comparisonTime = runComparison?.splitTimes?.getOrNull(index)
            if (completedTime != null && comparisonTime != null) {
                completedTime - comparisonTime
            } else {
                null
            }
        }
    } else {
        null
    }
    val activeSplitDeltaMillis = if (isRunning) {
        liveActiveSplitDeltaMillis(
            elapsedMillis = elapsedMillis,
            activeSplitIndex = activeSplitIndex,
            completedTimes = completedTimes,
            runComparison = runComparison
        )
    } else {
        null
    }
    val latestSplitDeltaMillis = activeSplitDeltaMillis ?: latestCompletedSplitDeltaMillis
    val timerTextColor = latestSplitDeltaMillis?.let {
        when {
            activeSplitDeltaMillis != null && it <= 0L -> LiveActiveSuccessGreen
            activeSplitDeltaMillis != null -> LiveActiveBehindRed
            it <= 0L -> SuccessGreen
            else -> BehindRed
        }
    } ?: PrimaryText
    val activePresetStats = presetStats[activePreset.presetName] ?: PresetStats()
    val liveUnpersistedRunMillis = if (isRunning) {
        (elapsedMillis - persistedCurrentRunMillis).coerceAtLeast(0L)
    } else {
        0L
    }
    val displayedTotalTimeMillis = activePresetStats.totalTimeMillis + liveUnpersistedRunMillis

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp)
        ) {
            val isWideThorShape = maxWidth > maxHeight
            val titleHeight = if (isWideThorShape) 82.dp else 92.dp
            val rowHeight = if (isWideThorShape) 42.dp else 48.dp
            val bottomHeight = if (isWideThorShape) 136.dp else 154.dp
            val titleSize = if (isWideThorShape) 22.sp else 24.sp
            val rowTextSize = if (isWideThorShape) 18.sp else 20.sp
            val timerSize = if (isWideThorShape) 54.sp else 62.sp
            val bottomButtonSide = if (isWideThorShape) 104.dp else 112.dp
            val splitButtonSize = ButtonSize(
                width = bottomButtonSide,
                height = bottomButtonSide
            )
            val resetButtonSize = ButtonSize(
                width = bottomButtonSide,
                height = bottomButtonSide
            )

            Column(modifier = Modifier.fillMaxSize()) {
                RunTitle(
                    game = activePreset.gameTitle,
                    category = activePreset.category,
                    fontSize = titleSize,
                    modifier = Modifier
                        .height(titleHeight)
                        .padding(
                            start = 24.dp,
                            end = if (isWideThorShape) 152.dp else 24.dp
                        )
                )
                SplitList(
                    splits = activePreset.segments,
                    completedTimes = completedTimes,
                    displayedComparisonRun = displayedComparisonRun,
                    runComparison = runComparison,
                    goldSplitIndices = goldSplitIndices,
                    elapsedMillis = elapsedMillis,
                    activeSplitIndex = activeSplitIndex,
                    isRunning = isRunning,
                    resetScrollRequest = resetScrollRequest,
                    isFinished = isFinished,
                    rowHeight = rowHeight,
                    rowTextSize = rowTextSize,
                    modifier = Modifier.weight(1f)
                )
                BottomControls(
                    buttonEnabled = true,
                    buttonText = if (isFinished) "DONE" else "SPLIT",
                    buttonSize = splitButtonSize,
                    resetButtonSize = resetButtonSize,
                    showResetButton = isRunning,
                    showUndoButton = isRunning,
                    undoButtonEnabled = isRunning && activeSplitIndex > 0,
                    sumOfBestText = sumOfBestText,
                    attemptedRuns = activePresetStats.attemptedRuns,
                    totalTimeText = formatDuration(displayedTotalTimeMillis),
                    timerText = formatSeconds(elapsedMillis),
                    timerColor = timerTextColor,
                    timerSize = timerSize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomHeight)
                        .padding(horizontal = 24.dp),
                    onSplit = {
                        if (isFinished) {
                            resetRun(activePreset.segments.size)
                            return@BottomControls
                        }

                        val pressTime = SystemClock.elapsedRealtime()
                        if (!isRunning) {
                            val presetName = activePreset.presetName
                            val currentStats = presetStats[presetName] ?: PresetStats()
                            presetStats[presetName] = currentStats.copy(
                                attemptedRuns = currentStats.attemptedRuns + 1
                            )
                            persistedCurrentRunMillis = 0L
                            runComparison = savedRunForActivePreset
                            isRunning = true
                            startedAtMillis = pressTime
                            nowMillis = pressTime
                            coroutineScope.launch {
                                splitPresetDao.ensurePresetExists(
                                    preset = activePreset.toSplitPresetEntity(currentStats),
                                    segments = activePreset.toSplitPresetSegmentEntities()
                                )
                                splitPresetDao.incrementAttemptedRuns(presetName)
                            }
                            return@BottomControls
                        }

                        val splitElapsed = pressTime - startedAtMillis
                        val previousSplitElapsed = if (activeSplitIndex == 0) {
                            0L
                        } else {
                            completedTimes.getOrNull(activeSplitIndex - 1) ?: 0L
                        }
                        val segmentElapsed = splitElapsed - previousSplitElapsed
                        val currentSegmentBest = activeBestSegments
                            ?.segmentTimes
                            ?.getOrNull(activeSplitIndex)
                        if (currentSegmentBest == null || segmentElapsed < currentSegmentBest) {
                            bestSegmentRollbackValues[activeSplitIndex] =
                                currentSegmentBest ?: UntimedSplitSentinel
                            val updatedSegmentTimes = MutableList(activePreset.segments.size) { index ->
                                activeBestSegments?.segmentTimes?.getOrNull(index)
                            }
                            updatedSegmentTimes[activeSplitIndex] = segmentElapsed
                            val updatedBestSegments = BestSegments(
                                presetName = activePreset.presetName,
                                segmentTimes = updatedSegmentTimes
                            )
                            savedBestSegments[activePreset.presetName] = updatedBestSegments
                            if (activeSplitIndex !in goldSplitIndices) {
                                goldSplitIndices.add(activeSplitIndex)
                            }
                            coroutineScope.launch {
                                bestSegmentsDao.upsert(updatedBestSegments.toBestSegmentsEntity())
                            }
                        }
                        completedTimes[activeSplitIndex] = splitElapsed

                        if (activeSplitIndex == activePreset.segments.lastIndex) {
                            isRunning = false
                            isFinished = true
                            finishedElapsedMillis = splitElapsed
                            nowMillis = pressTime
                            val remainingRunTime = splitElapsed - persistedCurrentRunMillis
                            if (remainingRunTime > 0L) {
                                val presetName = activePreset.presetName
                                val currentStats = presetStats[presetName] ?: PresetStats()
                                presetStats[presetName] = currentStats.copy(
                                    totalTimeMillis = currentStats.totalTimeMillis + remainingRunTime
                                )
                                persistedCurrentRunMillis = splitElapsed
                                coroutineScope.launch {
                                    splitPresetDao.addTotalTime(presetName, remainingRunTime)
                                }
                            }
                            val completedRun = Run(
                                presetName = activePreset.presetName,
                                splitTimes = completedTimes.mapIndexed { index, time ->
                                    when {
                                        index == activeSplitIndex -> splitElapsed
                                        time != null -> time
                                        else -> splitElapsed
                                    }
                                },
                                finalTime = splitElapsed,
                                completedAtMillis = System.currentTimeMillis()
                            )
                            val currentBest = savedRuns[activePreset.presetName]
                            val isNewPersonalBest =
                                currentBest == null ||
                                currentBest.splitTimes.size != completedRun.splitTimes.size ||
                                currentBest.splitTimes.any { it == null } ||
                                completedRun.finalTime < currentBest.finalTime
                            val historicalRun = HistoricalRun(
                                id = 0L,
                                presetName = activePreset.presetName,
                                gameTitle = activePreset.gameTitle,
                                category = activePreset.category,
                                splits = activePreset.segments.mapIndexed { index, segment ->
                                    HistoricalSplit(
                                        segmentName = segment.name,
                                        splitTimeMillis = completedRun.splitTimes[index]
                                            ?: splitElapsed,
                                        comparisonSplitTimeMillis = runComparison
                                            ?.splitTimes
                                            ?.getOrNull(index),
                                        wasGold = index in goldSplitIndices
                                    )
                                },
                                finalTimeMillis = splitElapsed,
                                completedAtMillis = completedRun.completedAtMillis,
                                wasPersonalBest = isNewPersonalBest
                            )
                            val completedPresetName = historicalRun.presetName
                            coroutineScope.launch {
                                val historyId = completedRunDao.insertWithSplits(
                                    run = historicalRun.toCompletedRunEntity(),
                                    splits = historicalRun.toCompletedRunSplitEntities()
                                )
                                val persistedHistoricalRun = historicalRun.copy(id = historyId)
                                val currentHistory = completedRunHistory[
                                    completedPresetName
                                ].orEmpty()
                                completedRunHistory[completedPresetName] =
                                    (listOf(persistedHistoricalRun) + currentHistory)
                                        .sortedByDescending { it.completedAtMillis }
                            }
                            if (isNewPersonalBest) {
                                savedRuns[activePreset.presetName] = completedRun
                                coroutineScope.launch {
                                    personalBestRunDao.upsert(completedRun.toPersonalBestRunEntity())
                                }
                            }
                        } else {
                            activeSplitIndex += 1
                            nowMillis = pressTime
                        }
                    },
                    onReset = {
                        if (isRunning) {
                            val resetTime = SystemClock.elapsedRealtime()
                            val elapsedThisRun = resetTime - startedAtMillis
                            val remainingRunTime = elapsedThisRun - persistedCurrentRunMillis
                            if (remainingRunTime > 0L) {
                                val presetName = activePreset.presetName
                                val currentStats = presetStats[presetName] ?: PresetStats()
                                presetStats[presetName] = currentStats.copy(
                                    totalTimeMillis = currentStats.totalTimeMillis + remainingRunTime
                                )
                                coroutineScope.launch {
                                    splitPresetDao.addTotalTime(presetName, remainingRunTime)
                                }
                            }
                        }
                        resetRun(activePreset.segments.size)
                    },
                    onUndo = {
                        if (isRunning && activeSplitIndex > 0) {
                            val undoneSplitIndex = activeSplitIndex - 1
                            completedTimes[undoneSplitIndex] = null
                            if (undoneSplitIndex in goldSplitIndices) {
                                goldSplitIndices.remove(undoneSplitIndex)
                                val previousBestSegment = bestSegmentRollbackValues
                                    .remove(undoneSplitIndex)
                                if (previousBestSegment != null) {
                                    val presetName = activePreset.presetName
                                    val restoredSegmentTimes = MutableList(
                                        activePreset.segments.size
                                    ) { index ->
                                        savedBestSegments[presetName]
                                            ?.segmentTimes
                                            ?.getOrNull(index)
                                    }
                                    restoredSegmentTimes[undoneSplitIndex] =
                                        previousBestSegment.takeIf { it >= 0L }
                                    if (restoredSegmentTimes.any { it != null }) {
                                        val restoredBestSegments = BestSegments(
                                            presetName = presetName,
                                            segmentTimes = restoredSegmentTimes
                                        )
                                        savedBestSegments[presetName] = restoredBestSegments
                                        coroutineScope.launch {
                                            bestSegmentsDao.upsert(
                                                restoredBestSegments.toBestSegmentsEntity()
                                            )
                                        }
                                    } else {
                                        savedBestSegments.remove(presetName)
                                        coroutineScope.launch {
                                            bestSegmentsDao.deleteByPresetName(presetName)
                                        }
                                    }
                                }
                            }
                            activeSplitIndex = undoneSplitIndex
                            nowMillis = SystemClock.elapsedRealtime()
                        }
                    }
                )
            }

            AnimatedVisibility(
                visible = isSettingsOpen,
                enter = fadeIn(animationSpec = tween(ButtonFadeMillis)),
                exit = fadeOut(animationSpec = tween(ButtonFadeMillis))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                )
            }
            AnimatedVisibility(
                visible = isSettingsOpen,
                enter = fadeIn(animationSpec = tween(ButtonFadeMillis)) +
                    scaleIn(
                        animationSpec = tween(ButtonFadeMillis),
                        initialScale = 0.96f
                    ),
                exit = fadeOut(animationSpec = tween(ButtonFadeMillis)) +
                    scaleOut(
                        animationSpec = tween(ButtonFadeMillis),
                        targetScale = 0.98f
                    ),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize()
            ) {
                SettingsPanel(
                    onClose = {
                        presetPendingDelete = null
                        isSettingsOpen = false
                    },
                    savedPresets = savedPresets,
                    activePreset = activePreset,
                    activePersonalBest = savedRuns[activePreset.presetName],
                    activeBestSegments = savedBestSegments[activePreset.presetName],
                    activeRunHistory = completedRunHistory[activePreset.presetName].orEmpty(),
                    backupExportState = backupExportState,
                    backupImportState = backupImportState,
                    selectedThemeMode = selectedThemeMode,
                    effectiveThemeMode = effectiveThemeMode,
                    useSystemTheme = useSystemTheme,
                    selectedFontMode = selectedFontMode,
                    updateCheckState = updateCheckState,
                    onOpenRelease = ::openReleasePage,
                    onSelectedThemeModeChange = { themeMode ->
                        selectedThemeMode = themeMode
                        useSystemTheme = false
                        coroutineScope.launch {
                            appPreferenceDao.upsert(
                                AppPreferenceEntity(
                                    key = ThemePreferenceKey,
                                    value = themeMode.storageValue
                                )
                            )
                            appPreferenceDao.upsert(
                                AppPreferenceEntity(
                                    key = UseSystemThemePreferenceKey,
                                    value = "false"
                                )
                            )
                        }
                    },
                    onUseSystemThemeChange = { enabled ->
                        useSystemTheme = enabled
                        coroutineScope.launch {
                            appPreferenceDao.upsert(
                                AppPreferenceEntity(
                                    key = UseSystemThemePreferenceKey,
                                    value = enabled.toString()
                                )
                            )
                        }
                    },
                    onSelectedFontModeChange = { fontMode ->
                        selectedFontMode = fontMode
                        coroutineScope.launch {
                            appPreferenceDao.upsert(
                                AppPreferenceEntity(
                                    key = FontPreferenceKey,
                                    value = fontMode.storageValue
                                )
                            )
                        }
                    },
                    onRequestBackup = { selectedPresetNames ->
                        backupImportState = BackupImportState.Idle
                        val backupBundle = createBackupBundle(
                            selectedPresetNames = selectedPresetNames,
                            activePresetName = activePreset.presetName,
                            savedPresets = savedPresets,
                            savedRuns = savedRuns,
                            savedBestSegments = savedBestSegments,
                            completedRunHistory = completedRunHistory,
                            presetStats = presetStats
                        )
                        if (backupBundle.presets.isNotEmpty()) {
                            pendingBackupBundle = backupBundle
                            backupExportState = BackupExportState.ChoosingFolder
                            backupFolderLauncher.launch(null)
                        }
                    },
                    onRequestBackupImport = {
                        backupExportState = BackupExportState.Idle
                        backupImportState = BackupImportState.ChoosingFile
                        backupFileLauncher.launch(
                            arrayOf(
                                "application/json",
                                "application/octet-stream",
                                "text/plain"
                            )
                        )
                    },
                    selectedTab = presetSettingsTab,
                    editPresetScrollRequest = editPresetScrollRequest,
                    onSelectedTabChange = { presetSettingsTab = it },
                    draftPresetName = draftPresetName,
                    onDraftPresetNameChange = { draftPresetName = it },
                    draftGameTitle = draftGameTitle,
                    onDraftGameTitleChange = { draftGameTitle = it },
                    draftCategory = draftCategory,
                    onDraftCategoryChange = { draftCategory = it },
                    draftSegments = draftSegments,
                    onDraftSegmentNameChange = { index, name ->
                        draftSegments[index] = draftSegments[index].copy(name = name)
                    },
                    onCycleDraftSegmentColor = { index ->
                        val currentColor = draftSegments[index].markerColor
                        draftSegments[index] = draftSegments[index].copy(
                            markerColor = nextPresetColor(currentColor)
                        )
                    },
                    onMoveDraftSegmentUp = { index ->
                        if (index > 0) {
                            draftSegments.add(index - 1, draftSegments.removeAt(index))
                        }
                    },
                    onMoveDraftSegmentDown = { index ->
                        if (index < draftSegments.lastIndex) {
                            draftSegments.add(index + 1, draftSegments.removeAt(index))
                        }
                    },
                    onDeleteDraftSegment = { index ->
                        if (draftSegments.size > 1) {
                            draftSegments.removeAt(index)
                        }
                    },
                    onAddDraftSegment = {
                        val nextIndex = draftSegments.size
                        draftSegments.add(
                            DraftSplitSegment(
                                id = nextDraftSegmentId,
                                name = "Split ${nextIndex + 1}",
                                markerColor = PresetColors[nextIndex % PresetColors.size]
                            )
                        )
                        nextDraftSegmentId += 1
                    },
                    onSaveDraftPreset = {
                        val preset = SplitPreset(
                            presetName = draftPresetName.ifBlank { "Preset ${savedPresets.size + 1}" },
                            gameTitle = draftGameTitle.ifBlank { "Game" },
                            category = draftCategory.ifBlank { "Any%" },
                            segments = draftSegments.mapIndexed { index, segment ->
                                SplitSegment(
                                    name = segment.name.ifBlank { "Split ${index + 1}" },
                                    markerColor = segment.markerColor
                                )
                            }
                        )
                        val existingIndex = savedPresets.indexOfFirst {
                            it.presetName == preset.presetName
                        }
                        if (existingIndex >= 0) {
                            if (savedPresets[existingIndex] != preset) {
                                savedRuns.remove(preset.presetName)
                                savedBestSegments.remove(preset.presetName)
                                coroutineScope.launch {
                                    personalBestRunDao.deleteByPresetName(preset.presetName)
                                    bestSegmentsDao.deleteByPresetName(preset.presetName)
                                }
                            }
                            savedPresets[existingIndex] = preset
                        } else {
                            savedPresets.add(preset)
                        }
                        if (preset.presetName != DefaultPreset.presetName) {
                            val currentStats = presetStats[preset.presetName] ?: PresetStats()
                            presetStats[preset.presetName] = currentStats
                            coroutineScope.launch {
                                splitPresetDao.upsertWithSegments(
                                    preset = preset.toSplitPresetEntity(currentStats),
                                    segments = preset.toSplitPresetSegmentEntities()
                                )
                            }
                        }
                        loadPreset(preset)
                    },
                    editTargetPresetName = editTargetPresetName,
                    editGameTitle = editGameTitle,
                    onEditGameTitleChange = { editGameTitle = it },
                    editCategory = editCategory,
                    onEditCategoryChange = { editCategory = it },
                    editSegments = editSegments,
                    onStartEditPreset = { preset ->
                        if (preset.presetName != DefaultPreset.presetName) {
                            startEditingPreset(preset)
                            presetSettingsTab = PresetSettingsTab.Edit
                            editPresetScrollRequest += 1
                        }
                    },
                    onEditSegmentNameChange = { index, name ->
                        editSegments[index] = editSegments[index].copy(name = name)
                    },
                    onCycleEditSegmentColor = { index ->
                        val currentColor = editSegments[index].markerColor
                        editSegments[index] = editSegments[index].copy(
                            markerColor = nextPresetColor(currentColor)
                        )
                    },
                    onMoveEditSegmentUp = { index ->
                        if (index > 0) {
                            editSegments.add(index - 1, editSegments.removeAt(index))
                        }
                    },
                    onMoveEditSegmentDown = { index ->
                        if (index < editSegments.lastIndex) {
                            editSegments.add(index + 1, editSegments.removeAt(index))
                        }
                    },
                    onDeleteEditSegment = { index ->
                        if (editSegments.size > 1) {
                            editSegments.removeAt(index)
                        }
                    },
                    onAddEditSegment = {
                        val nextIndex = editSegments.size
                        editSegments.add(
                            DraftSplitSegment(
                                id = nextEditSegmentId,
                                name = "Split ${nextIndex + 1}",
                                markerColor = PresetColors[nextIndex % PresetColors.size]
                            )
                        )
                        nextEditSegmentId += 1
                    },
                    onSaveEditedPreset = {
                        val targetPresetName = editTargetPresetName
                        if (targetPresetName != null) {
                            val existingIndex = savedPresets.indexOfFirst {
                                it.presetName == targetPresetName
                            }
                            if (existingIndex >= 0 && targetPresetName != DefaultPreset.presetName) {
                                val editedPreset = SplitPreset(
                                    presetName = targetPresetName,
                                    gameTitle = editGameTitle.ifBlank { "Game" },
                                    category = editCategory.ifBlank { "Any%" },
                                    segments = editSegments.mapIndexed { index, segment ->
                                        SplitSegment(
                                            name = segment.name.ifBlank { "Split ${index + 1}" },
                                            markerColor = segment.markerColor
                                        )
                                    }
                                )
                                val oldPreset = savedPresets[existingIndex]
                                if (oldPreset != editedPreset) {
                                    val migratedBestSegments = savedBestSegments[targetPresetName]?.let {
                                        migrateBestSegmentsForEditedPreset(
                                            oldPreset = oldPreset,
                                            editedPreset = editedPreset,
                                            bestSegments = it
                                        )
                                    }
                                    if (migratedBestSegments != null) {
                                        savedBestSegments[targetPresetName] = migratedBestSegments
                                        coroutineScope.launch {
                                            bestSegmentsDao.upsert(
                                                migratedBestSegments.toBestSegmentsEntity()
                                            )
                                        }
                                    } else {
                                        savedBestSegments.remove(targetPresetName)
                                        coroutineScope.launch {
                                            bestSegmentsDao.deleteByPresetName(targetPresetName)
                                        }
                                    }

                                    val migratedRun = savedRuns[targetPresetName]?.let { run ->
                                        migrateRunForEditedPreset(
                                            oldPreset = oldPreset,
                                            editedPreset = editedPreset,
                                            run = run
                                        )
                                    }
                                    if (migratedRun != null) {
                                        savedRuns[targetPresetName] = migratedRun
                                        if (runComparison?.presetName == targetPresetName) {
                                            runComparison = migratedRun
                                        }
                                        coroutineScope.launch {
                                            personalBestRunDao.upsert(
                                                migratedRun.toPersonalBestRunEntity()
                                            )
                                        }
                                    } else {
                                        savedRuns.remove(targetPresetName)
                                        if (runComparison?.presetName == targetPresetName) {
                                            runComparison = null
                                        }
                                        coroutineScope.launch {
                                            personalBestRunDao.deleteByPresetName(targetPresetName)
                                        }
                                    }
                                }
                                savedPresets[existingIndex] = editedPreset
                                val currentStats = presetStats[targetPresetName] ?: PresetStats()
                                presetStats[targetPresetName] = currentStats
                                coroutineScope.launch {
                                    splitPresetDao.upsertWithSegments(
                                        preset = editedPreset.toSplitPresetEntity(currentStats),
                                        segments = editedPreset.toSplitPresetSegmentEntities()
                                    )
                                }
                                if (activePreset.presetName == targetPresetName) {
                                    activePreset = editedPreset
                                    resetRun(editedPreset.segments.size)
                                }
                            }
                        }
                    },
                    onLoadPreset = ::loadPreset,
                    onClearPersonalBest = { preset ->
                        savedRuns.remove(preset.presetName)
                        if (runComparison?.presetName == preset.presetName) {
                            runComparison = null
                        }
                        coroutineScope.launch {
                            personalBestRunDao.deleteByPresetName(preset.presetName)
                        }
                    },
                    onClearBestSegment = { preset, index ->
                        if (preset.presetName == activePreset.presetName) {
                            goldSplitIndices.remove(index)
                        }
                        val currentBestSegments = savedBestSegments[preset.presetName]
                        val updatedSegmentTimes = MutableList(preset.segments.size) { segmentIndex ->
                            currentBestSegments?.segmentTimes?.getOrNull(segmentIndex)
                        }
                        if (index in updatedSegmentTimes.indices) {
                            updatedSegmentTimes[index] = null
                            val hasRemainingSegments = updatedSegmentTimes.any { it != null }
                            if (hasRemainingSegments) {
                                val updatedBestSegments = BestSegments(
                                    presetName = preset.presetName,
                                    segmentTimes = updatedSegmentTimes
                                )
                                savedBestSegments[preset.presetName] = updatedBestSegments
                                coroutineScope.launch {
                                    bestSegmentsDao.upsert(updatedBestSegments.toBestSegmentsEntity())
                                }
                            } else {
                                savedBestSegments.remove(preset.presetName)
                                coroutineScope.launch {
                                    bestSegmentsDao.deleteByPresetName(preset.presetName)
                                }
                            }
                        }
                    },
                    onClearBestSegments = { preset ->
                        savedBestSegments.remove(preset.presetName)
                        if (preset.presetName == activePreset.presetName) {
                            goldSplitIndices.clear()
                        }
                        coroutineScope.launch {
                            bestSegmentsDao.deleteByPresetName(preset.presetName)
                        }
                    },
                    onDeletePreset = { preset ->
                        if (preset.presetName != DefaultPreset.presetName) {
                            presetPendingDelete = preset
                        }
                    },
                    onResetDefault = {
                        draftPresetName = "New Preset"
                        draftGameTitle = DefaultPreset.gameTitle
                        draftCategory = DefaultPreset.category
                        nextDraftSegmentId = DefaultPreset.segments.size
                        draftSegments.clear()
                        draftSegments.addAll(
                            DefaultPreset.segments.take(4).mapIndexed { index, segment ->
                                DraftSplitSegment(
                                    id = index,
                                    name = segment.name,
                                    markerColor = segment.markerColor
                                )
                            }
                        )
                        loadPreset(DefaultPreset)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                )
            }

            AnimatedVisibility(
                visible = presetPendingDelete != null,
                enter = fadeIn(animationSpec = tween(ButtonFadeMillis)) +
                    scaleIn(
                        animationSpec = tween(ButtonFadeMillis),
                        initialScale = 0.96f
                    ),
                exit = fadeOut(animationSpec = tween(ButtonFadeMillis)) +
                    scaleOut(
                        animationSpec = tween(ButtonFadeMillis),
                        targetScale = 0.98f
                    ),
                modifier = Modifier.fillMaxSize()
            ) {
                val presetToDelete = presetPendingDelete
                if (presetToDelete != null) {
                    DeletePresetConfirmationDialog(
                        onCancel = { presetPendingDelete = null },
                        onConfirm = {
                            presetPendingDelete = null
                            deletePreset(presetToDelete)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            AnimatedVisibility(
                visible = !isSettingsOpen,
                enter = fadeIn(animationSpec = tween(ButtonFadeMillis)) +
                    scaleIn(
                        animationSpec = tween(ButtonFadeMillis),
                        initialScale = 0.92f
                    ),
                exit = fadeOut(animationSpec = tween(ButtonFadeMillis)) +
                    scaleOut(
                        animationSpec = tween(ButtonFadeMillis),
                        targetScale = 0.92f
                    ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 24.dp)
            ) {
                SettingsButton(
                    onClick = { isSettingsOpen = true },
                    modifier = Modifier.size(width = 104.dp, height = 48.dp)
                )
            }
        }
    }
    }
}

private data class ButtonSize(
    val width: Dp,
    val height: Dp
)

private enum class PresetSettingsTab {
    Create,
    Edit,
    Records,
    History,
    Backup
}

@Composable
private fun rememberButtonVibration(): () -> Unit {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        { performButtonVibration(context) }
    }
}

private fun performButtonVibration(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } ?: return

    if (!vibrator.hasVibrator()) {
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    } else {
        vibrator.vibrate(
            VibrationEffect.createOneShot(ButtonVibrationMillis, ButtonVibrationAmplitude)
        )
    }
}

@Composable
private fun RunTitle(
    game: String,
    category: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val gameFontSize = adjustedTitleFontSize(game, fontSize)
    val categoryFontSize = adjustedTitleFontSize(category, fontSize)

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = game,
            color = PrimaryText,
            fontSize = gameFontSize,
            lineHeight = gameFontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = category,
            color = PrimaryText,
            fontSize = categoryFontSize,
            lineHeight = categoryFontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun adjustedTitleFontSize(text: String, baseSize: TextUnit): TextUnit {
    val scale = when {
        text.length >= 36 -> 0.58f
        text.length >= 30 -> 0.66f
        text.length >= 24 -> 0.78f
        text.length >= 18 -> 0.9f
        else -> 1f
    }
    return (baseSize.value * scale).sp
}

@Composable
private fun PresetStatsPanel(
    sumOfBestText: String?,
    attemptedRuns: Int,
    totalTimeText: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        if (sumOfBestText != null) {
            Text(
                text = "Sum of Best: $sumOfBestText",
                color = SecondaryText,
                fontSize = 13.sp,
                lineHeight = 13.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = "Attempts $attemptedRuns",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 13.sp,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Total $totalTimeText",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 13.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun SplitList(
    splits: List<SplitSegment>,
    completedTimes: List<Long?>,
    displayedComparisonRun: Run?,
    runComparison: Run?,
    goldSplitIndices: List<Int>,
    elapsedMillis: Long,
    activeSplitIndex: Int,
    isRunning: Boolean,
    resetScrollRequest: Int,
    isFinished: Boolean,
    rowHeight: Dp,
    rowTextSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val oneAwayVisibleIndex = when {
        splits.isEmpty() -> 0
        activeSplitIndex == 0 && !isFinished -> 0
        isFinished -> activeSplitIndex.coerceIn(splits.indices)
        activeSplitIndex < splits.lastIndex -> activeSplitIndex + 1
        else -> activeSplitIndex
    }

    LaunchedEffect(resetScrollRequest) {
        if (resetScrollRequest > 0) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(oneAwayVisibleIndex) {
        if (oneAwayVisibleIndex == 0) {
            listState.animateScrollToItem(0)
            return@LaunchedEffect
        }

        val layoutInfo = listState.layoutInfo
        val viewportStart = layoutInfo.viewportStartOffset
        val viewportEnd = layoutInfo.viewportEndOffset
        val visibleItems = layoutInfo.visibleItemsInfo
        val targetItem = visibleItems.firstOrNull { it.index == oneAwayVisibleIndex }
        val isTargetFullyVisible = targetItem != null &&
            targetItem.offset >= viewportStart &&
            targetItem.offset + targetItem.size <= viewportEnd

        if (!isTargetFullyVisible) {
            val fullyVisibleRowCount = visibleItems.count { item ->
                item.offset >= viewportStart && item.offset + item.size <= viewportEnd
            }.coerceAtLeast(1)
            val firstVisibleIndex = (oneAwayVisibleIndex - fullyVisibleRowCount + 1)
                .coerceAtLeast(0)
            listState.animateScrollToItem(firstVisibleIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
    ) {
        itemsIndexed(
            items = splits,
            key = { _, split -> split.name }
        ) { index, split ->
            val personalBestTime = displayedComparisonRun?.splitTimes?.getOrNull(index)
            val currentRunTime = completedTimes[index]
            val displayedTime = personalBestTime ?: currentRunTime
            val isGoldSplit = index in goldSplitIndices
            val isActiveSplit = index == activeSplitIndex && !isFinished
            val deltaMillis = runComparison?.splitTimes?.getOrNull(index)?.let { comparisonTime ->
                when {
                    completedTimes[index] != null -> {
                        completedTimes[index]?.minus(comparisonTime)
                    }
                    isActiveSplit && isRunning -> {
                        liveActiveSplitDeltaMillis(
                            elapsedMillis = elapsedMillis,
                            activeSplitIndex = activeSplitIndex,
                            completedTimes = completedTimes,
                            runComparison = runComparison
                        )
                    }
                    else -> null
                }
            }
            SplitRow(
                split = split,
                comparisonTime = displayedTime?.let(::formatSeconds) ?: "--",
                hasComparisonTime = displayedTime != null,
                comparisonTimeColor = if (isGoldSplit && deltaMillis == null) {
                    GoldSplit
                } else {
                    null
                },
                deltaText = deltaMillis?.let(::formatDeltaSeconds),
                deltaColor = deltaMillis?.let {
                    when {
                        isGoldSplit -> GoldSplit
                        isActiveSplit && isRunning && it <= 0L -> LiveActiveSuccessGreen
                        isActiveSplit && isRunning -> LiveActiveBehindRed
                        it <= 0L -> SuccessGreen
                        else -> BehindRed
                    }
                } ?: SecondaryText,
                isActive = isActiveSplit,
                rowHeight = rowHeight,
                textSize = rowTextSize
            )
        }
    }
}

@Composable
private fun SplitRow(
    split: SplitSegment,
    comparisonTime: String,
    hasComparisonTime: Boolean,
    comparisonTimeColor: Color?,
    deltaText: String?,
    deltaColor: Color,
    isActive: Boolean,
    rowHeight: Dp,
    textSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val rowBackground = if (isActive) ActiveRowBackground else RowBlack
    val timeColor = comparisonTimeColor ?: if (hasComparisonTime) PrimaryText else SecondaryText

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(rowBackground)
            .border(width = 0.5.dp, color = DividerColor)
            .padding(horizontal = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(7.dp)
                .fillMaxHeight(0.72f)
                .background(split.markerColor)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = split.name,
            color = PrimaryText,
            fontSize = textSize,
            lineHeight = textSize,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (deltaText != null) {
            Text(
                text = deltaText,
                color = deltaColor,
                fontSize = textSize,
                lineHeight = textSize,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(92.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = comparisonTime,
            color = timeColor,
            fontSize = textSize,
            lineHeight = textSize,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(92.dp)
        )
    }
}

@Composable
private fun BottomControls(
    buttonEnabled: Boolean,
    buttonText: String,
    buttonSize: ButtonSize,
    resetButtonSize: ButtonSize,
    showResetButton: Boolean,
    showUndoButton: Boolean,
    undoButtonEnabled: Boolean,
    sumOfBestText: String?,
    attemptedRuns: Int,
    totalTimeText: String,
    timerText: String,
    timerColor: Color,
    timerSize: TextUnit,
    onSplit: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
    ) {
        SplitButton(
            enabled = buttonEnabled,
            text = buttonText,
            onSplit = onSplit,
            fontSize = 22.sp,
            modifier = Modifier.size(width = buttonSize.width, height = buttonSize.height)
        )
        AnimatedVisibility(
            visible = showResetButton || showUndoButton,
            enter = fadeIn(animationSpec = tween(ButtonFadeMillis)) +
                scaleIn(
                    animationSpec = tween(ButtonFadeMillis),
                    initialScale = 0.92f
                ),
            exit = fadeOut(animationSpec = tween(ButtonFadeMillis)) +
                scaleOut(
                    animationSpec = tween(ButtonFadeMillis),
                    targetScale = 0.92f
                )
        ) {
            Row {
                Spacer(modifier = Modifier.width(12.dp))
                if (showUndoButton) {
                    val secondaryButtonHeight = (resetButtonSize.height - 8.dp) / 2
                    Column {
                        SplitButton(
                            enabled = undoButtonEnabled,
                            text = "UNDO",
                            onSplit = onUndo,
                            fontSize = 16.sp,
                            modifier = Modifier.size(
                                width = resetButtonSize.width,
                                height = secondaryButtonHeight
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SplitButton(
                            enabled = showResetButton,
                            text = "RESET",
                            onSplit = onReset,
                            fontSize = 16.sp,
                            modifier = Modifier.size(
                                width = resetButtonSize.width,
                                height = secondaryButtonHeight
                            )
                        )
                    }
                } else {
                    SplitButton(
                        enabled = showResetButton,
                        text = "RESET",
                        onSplit = onReset,
                        fontSize = 20.sp,
                        modifier = Modifier.size(
                            width = resetButtonSize.width,
                            height = resetButtonSize.height
                        )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom
        ) {
            PresetStatsPanel(
                sumOfBestText = sumOfBestText,
                attemptedRuns = attemptedRuns,
                totalTimeText = totalTimeText
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = timerText,
                color = timerColor,
                fontSize = timerSize,
                lineHeight = timerSize,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DeletePresetConfirmationDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(Color(0xCC000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(horizontal = 40.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RowBlack)
                .border(width = 1.5.dp, color = DividerColor)
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Text(
                text = "Are you sure you want to delete this preset?",
                color = PrimaryText,
                fontSize = 19.sp,
                lineHeight = 19.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                PanelTextButton(
                    text = "CANCEL",
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                )
                DangerPanelTextButton(
                    text = "CONFIRM",
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val vibrate = rememberButtonVibration()
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) ActiveRowBackground else RowBlack,
        animationSpec = tween(ButtonFadeMillis),
        label = "settingsButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) PrimaryText else DividerColor,
        animationSpec = tween(ButtonFadeMillis),
        label = "settingsButtonBorder"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(backgroundColor)
            .border(width = 1.5.dp, color = borderColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    vibrate()
                    onClick()
                }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "Settings",
            tint = PrimaryText,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun SettingsPanel(
    onClose: () -> Unit,
    savedPresets: List<SplitPreset>,
    activePreset: SplitPreset,
    activePersonalBest: Run?,
    activeBestSegments: BestSegments?,
    activeRunHistory: List<HistoricalRun>,
    backupExportState: BackupExportState,
    backupImportState: BackupImportState,
    selectedThemeMode: AppThemeMode,
    effectiveThemeMode: AppThemeMode,
    useSystemTheme: Boolean,
    selectedFontMode: AppFontMode,
    updateCheckState: UpdateCheckState,
    onOpenRelease: (String) -> Unit,
    onSelectedThemeModeChange: (AppThemeMode) -> Unit,
    onUseSystemThemeChange: (Boolean) -> Unit,
    onSelectedFontModeChange: (AppFontMode) -> Unit,
    onRequestBackup: (Set<String>) -> Unit,
    onRequestBackupImport: () -> Unit,
    selectedTab: PresetSettingsTab,
    editPresetScrollRequest: Int,
    onSelectedTabChange: (PresetSettingsTab) -> Unit,
    draftPresetName: String,
    onDraftPresetNameChange: (String) -> Unit,
    draftGameTitle: String,
    onDraftGameTitleChange: (String) -> Unit,
    draftCategory: String,
    onDraftCategoryChange: (String) -> Unit,
    draftSegments: List<DraftSplitSegment>,
    onDraftSegmentNameChange: (Int, String) -> Unit,
    onCycleDraftSegmentColor: (Int) -> Unit,
    onMoveDraftSegmentUp: (Int) -> Unit,
    onMoveDraftSegmentDown: (Int) -> Unit,
    onDeleteDraftSegment: (Int) -> Unit,
    onAddDraftSegment: () -> Unit,
    onSaveDraftPreset: () -> Unit,
    editTargetPresetName: String?,
    editGameTitle: String,
    onEditGameTitleChange: (String) -> Unit,
    editCategory: String,
    onEditCategoryChange: (String) -> Unit,
    editSegments: List<DraftSplitSegment>,
    onStartEditPreset: (SplitPreset) -> Unit,
    onEditSegmentNameChange: (Int, String) -> Unit,
    onCycleEditSegmentColor: (Int) -> Unit,
    onMoveEditSegmentUp: (Int) -> Unit,
    onMoveEditSegmentDown: (Int) -> Unit,
    onDeleteEditSegment: (Int) -> Unit,
    onAddEditSegment: () -> Unit,
    onSaveEditedPreset: () -> Unit,
    onLoadPreset: (SplitPreset) -> Unit,
    onClearPersonalBest: (SplitPreset) -> Unit,
    onClearBestSegment: (SplitPreset, Int) -> Unit,
    onClearBestSegments: (SplitPreset) -> Unit,
    onDeletePreset: (SplitPreset) -> Unit,
    onResetDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settingsListState = rememberLazyListState()
    var selectedHistoricalRunId by remember(activePreset.presetName) {
        mutableStateOf<Long?>(null)
    }
    val availableBackupPresetNames = savedPresets.map { it.presetName }
    var selectedBackupPresetNames by remember {
        mutableStateOf(setOf(activePreset.presetName))
    }

    LaunchedEffect(editPresetScrollRequest, selectedTab) {
        if (editPresetScrollRequest > 0 && selectedTab == PresetSettingsTab.Edit) {
            settingsListState.animateScrollToItem(1)
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != PresetSettingsTab.History) {
            selectedHistoricalRunId = null
        }
    }

    LaunchedEffect(availableBackupPresetNames) {
        selectedBackupPresetNames = selectedBackupPresetNames
            .intersect(availableBackupPresetNames.toSet())
            .ifEmpty { setOf(activePreset.presetName) }
    }

    Column(
        modifier = modifier
            .background(OledBlack)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .background(RowBlack)
                .border(width = 0.5.dp, color = DividerColor)
                .padding(start = 24.dp, end = 14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Settings",
                    color = PrimaryText,
                    fontSize = 24.sp,
                    lineHeight = 24.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(10.dp))
                UpdateCheckRow(
                    updateCheckState = updateCheckState,
                    onOpenRelease = onOpenRelease
                )
            }
            CloseButton(
                onClick = onClose,
                modifier = Modifier.size(52.dp)
            )
        }

        LazyColumn(
            state = settingsListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            item {
                SettingsSectionTitle("Theme")
                ThemeModeToggle(
                    selectedThemeMode = selectedThemeMode,
                    effectiveThemeMode = effectiveThemeMode,
                    useSystemTheme = useSystemTheme,
                    onUseSystemThemeChange = onUseSystemThemeChange,
                    onSelectedThemeModeChange = onSelectedThemeModeChange
                )
                Spacer(modifier = Modifier.height(12.dp))
                FontModeToggle(
                    selectedFontMode = selectedFontMode,
                    onSelectedFontModeChange = onSelectedFontModeChange
                )
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionTitle("Presets")
                savedPresets.forEach { preset ->
                    PresetLoadRow(
                        preset = preset,
                        isActive = preset.presetName == activePreset.presetName,
                        isEditing = preset.presetName == editTargetPresetName,
                        canDelete = preset.presetName != DefaultPreset.presetName,
                        onLoad = { onLoadPreset(preset) },
                        onEdit = { onStartEditPreset(preset) },
                        onDelete = { onDeletePreset(preset) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                SettingsModeTabs(
                    selectedTab = selectedTab,
                    onSelectedTabChange = onSelectedTabChange
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (selectedTab == PresetSettingsTab.Create) {
                item {
                    SettingsSectionTitle("Create New")
                    LabeledTextInput(
                        label = "Preset Name",
                        value = draftPresetName,
                        onValueChange = onDraftPresetNameChange
                    )
                    LabeledTextInput(
                        label = "Game Title",
                        value = draftGameTitle,
                        onValueChange = onDraftGameTitleChange
                    )
                    LabeledTextInput(
                        label = "Category",
                        value = draftCategory,
                        onValueChange = onDraftCategoryChange
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                itemsIndexed(
                    items = draftSegments,
                    key = { _, segment -> "draft-${segment.id}" }
                ) { index, segment ->
                    EditableSegmentRow(
                        index = index,
                        segment = segment,
                        onNameChange = { onDraftSegmentNameChange(index, it) },
                        onCycleColor = { onCycleDraftSegmentColor(index) },
                        canMoveUp = index > 0,
                        canMoveDown = index < draftSegments.lastIndex,
                        canDelete = draftSegments.size > 1,
                        onMoveUp = { onMoveDraftSegmentUp(index) },
                        onMoveDown = { onMoveDraftSegmentDown(index) },
                        onDelete = { onDeleteDraftSegment(index) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        PanelTextButton(
                            text = "ADD ROW",
                            onClick = onAddDraftSegment,
                            modifier = Modifier.size(width = 96.dp, height = 40.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        PanelTextButton(
                            text = "SAVE PRESET",
                            onClick = onSaveDraftPreset,
                            modifier = Modifier.size(width = 138.dp, height = 40.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        PanelTextButton(
                            text = "RESET TO DEFAULT",
                            onClick = onResetDefault,
                            modifier = Modifier.size(width = 148.dp, height = 40.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(22.dp))
                }
            } else if (selectedTab == PresetSettingsTab.Edit) {
                item {
                    SettingsSectionTitle("Edit Selected")
                    if (editTargetPresetName == null) {
                        Text(
                            text = "Choose EDIT on a custom preset.",
                            color = SecondaryText,
                            fontSize = 15.sp,
                            lineHeight = 15.sp,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                    } else {
                        Text(
                            text = editTargetPresetName,
                            color = SuccessGreen,
                            fontSize = 15.sp,
                            lineHeight = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LabeledTextInput(
                            label = "Game Title",
                            value = editGameTitle,
                            onValueChange = onEditGameTitleChange
                        )
                        LabeledTextInput(
                            label = "Category",
                            value = editCategory,
                            onValueChange = onEditCategoryChange
                        )
                    }
                }

                if (editTargetPresetName != null) {
                    itemsIndexed(
                        items = editSegments,
                        key = { _, segment -> "edit-${segment.id}" }
                    ) { index, segment ->
                        EditableSegmentRow(
                            index = index,
                            segment = segment,
                            onNameChange = { onEditSegmentNameChange(index, it) },
                            onCycleColor = { onCycleEditSegmentColor(index) },
                            canMoveUp = index > 0,
                            canMoveDown = index < editSegments.lastIndex,
                            canDelete = editSegments.size > 1,
                            onMoveUp = { onMoveEditSegmentUp(index) },
                            onMoveDown = { onMoveEditSegmentDown(index) },
                            onDelete = { onDeleteEditSegment(index) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            PanelTextButton(
                                text = "ADD ROW",
                                onClick = onAddEditSegment,
                                modifier = Modifier.size(width = 96.dp, height = 40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            PanelTextButton(
                                text = "SAVE CHANGES",
                                onClick = onSaveEditedPreset,
                                modifier = Modifier.size(width = 126.dp, height = 40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            } else if (selectedTab == PresetSettingsTab.Records) {
                item {
                    RecordsPanel(
                        preset = activePreset,
                        personalBest = activePersonalBest,
                        bestSegments = activeBestSegments,
                        onClearPersonalBest = { onClearPersonalBest(activePreset) },
                        onClearBestSegment = { index -> onClearBestSegment(activePreset, index) },
                        onClearBestSegments = { onClearBestSegments(activePreset) }
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                }
            } else if (selectedTab == PresetSettingsTab.History) {
                val selectedHistoricalRun = activeRunHistory.firstOrNull {
                    it.id == selectedHistoricalRunId
                }
                if (selectedHistoricalRun == null) {
                    item {
                        HistoryHeader(
                            preset = activePreset,
                            completedRunCount = activeRunHistory.size
                        )
                    }
                    itemsIndexed(
                        items = activeRunHistory,
                        key = { _, run -> "history-${run.id}" }
                    ) { _, run ->
                        HistoricalRunRow(
                            run = run,
                            onOpenDetails = { selectedHistoricalRunId = run.id }
                        )
                    }
                    if (activeRunHistory.isEmpty()) {
                        item {
                            Text(
                                text = "Completed runs will appear here.",
                                color = SecondaryText,
                                fontSize = 15.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(22.dp))
                        }
                    }
                } else {
                    item {
                        HistoricalRunDetailsHeader(
                            run = selectedHistoricalRun,
                            onBack = { selectedHistoricalRunId = null }
                        )
                        HistoricalRunDetailsColumnHeader()
                    }
                    itemsIndexed(
                        items = selectedHistoricalRun.splits,
                        key = { index, _ -> "history-detail-${selectedHistoricalRun.id}-$index" }
                    ) { index, split ->
                        HistoricalRunSplitRow(
                            run = selectedHistoricalRun,
                            index = index,
                            split = split
                        )
                    }
                    item { Spacer(modifier = Modifier.height(22.dp)) }
                }
            } else {
                item {
                    BackupHeader(
                        selectedPresetCount = selectedBackupPresetNames.size,
                        backupExportState = backupExportState,
                        backupImportState = backupImportState
                    )
                }
                itemsIndexed(
                    items = savedPresets,
                    key = { _, preset -> "backup-${preset.presetName}" }
                ) { _, preset ->
                    val isSelected = preset.presetName in selectedBackupPresetNames
                    BackupPresetSelectionRow(
                        preset = preset,
                        selected = isSelected,
                        enabled = backupExportState !is BackupExportState.Exporting &&
                            backupImportState !is BackupImportState.Importing,
                        onToggle = {
                            selectedBackupPresetNames = if (isSelected) {
                                selectedBackupPresetNames - preset.presetName
                            } else {
                                selectedBackupPresetNames + preset.presetName
                            }
                        }
                    )
                }
                item {
                    val isBusy = backupExportState is BackupExportState.Exporting ||
                        backupImportState is BackupImportState.Importing
                    Row(modifier = Modifier.fillMaxWidth()) {
                        PanelTextButton(
                            text = "SELECT ALL",
                            onClick = {
                                selectedBackupPresetNames = availableBackupPresetNames.toSet()
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        PanelTextButton(
                            text = "CLEAR",
                            onClick = { selectedBackupPresetNames = emptySet() },
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        PanelTextButton(
                            text = "BACKUP",
                            onClick = { onRequestBackup(selectedBackupPresetNames) },
                            enabled = selectedBackupPresetNames.isNotEmpty() && !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        PanelTextButton(
                            text = "IMPORT",
                            onClick = onRequestBackupImport,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(22.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordsPanel(
    preset: SplitPreset,
    personalBest: Run?,
    bestSegments: BestSegments?,
    onClearPersonalBest: () -> Unit,
    onClearBestSegment: (Int) -> Unit,
    onClearBestSegments: () -> Unit
) {
    val matchingPersonalBest = personalBest
        ?.takeIf { it.splitTimes.size == preset.segments.size }
    val matchingBestSegments = bestSegments
        ?.takeIf { it.segmentTimes.size == preset.segments.size }
    val hasPersonalBest = matchingPersonalBest != null
    val hasBestSegments = matchingBestSegments?.segmentTimes?.any { it != null } == true

    SettingsSectionTitle("Records")
    Text(
        text = "${preset.gameTitle} - ${preset.category}",
        color = SuccessGreen,
        fontSize = 18.sp,
        lineHeight = 18.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "${preset.presetName}  |  ${preset.segments.size} rows",
        color = SecondaryText,
        fontSize = 13.sp,
        lineHeight = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = matchingPersonalBest?.let { "PB ${formatDateTime(it.completedAtMillis)}" }
            ?: "PB --",
        color = SecondaryText,
        fontSize = 13.sp,
        lineHeight = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row {
        PanelTextButton(
            text = "CLEAR PB",
            onClick = onClearPersonalBest,
            enabled = hasPersonalBest,
            modifier = Modifier.size(width = 112.dp, height = 40.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        PanelTextButton(
            text = "CLEAR GOLDS",
            onClick = onClearBestSegments,
            enabled = hasBestSegments,
            modifier = Modifier.size(width = 126.dp, height = 40.dp)
        )
    }
    Spacer(modifier = Modifier.height(12.dp))

    RecordsHeaderRow()
    preset.segments.forEachIndexed { index, split ->
        val pbTime = matchingPersonalBest?.splitTimes?.getOrNull(index)
        val bestSegmentTime = matchingBestSegments?.segmentTimes?.getOrNull(index)
        RecordSplitRow(
            index = index,
            split = split,
            personalBestTime = pbTime,
            bestSegmentTime = bestSegmentTime,
            onClearBestSegment = { onClearBestSegment(index) }
        )
    }
}

@Composable
private fun RecordsHeaderRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(RowBlack)
            .border(width = 0.5.dp, color = DividerColor)
            .padding(horizontal = 10.dp)
    ) {
        Text(
            text = "Split",
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "PB",
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(78.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "GOLD",
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(78.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Spacer(modifier = Modifier.width(66.dp))
    }
}

@Composable
private fun RecordSplitRow(
    index: Int,
    split: SplitSegment,
    personalBestTime: Long?,
    bestSegmentTime: Long?,
    onClearBestSegment: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(RowBlack)
            .border(width = 0.5.dp, color = DividerColor)
            .padding(horizontal = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .fillMaxHeight(0.65f)
                .background(split.markerColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "${index + 1}. ${split.name}",
            color = PrimaryText,
            fontSize = 15.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = personalBestTime?.let(::formatSeconds) ?: "--",
            color = if (personalBestTime == null) SecondaryText else PrimaryText,
            fontSize = 14.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(78.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = bestSegmentTime?.let(::formatSeconds) ?: "--",
            color = if (bestSegmentTime == null) SecondaryText else GoldSplit,
            fontSize = 14.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(78.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        PanelIconButton(
            imageVector = Icons.Filled.Close,
            contentDescription = "Clear best segment",
            onClick = onClearBestSegment,
            enabled = bestSegmentTime != null,
            modifier = Modifier.size(width = 66.dp, height = 30.dp)
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun HistoryHeader(
    preset: SplitPreset,
    completedRunCount: Int
) {
    SettingsSectionTitle("Run History")
    Text(
        text = "${preset.gameTitle} - ${preset.category}",
        color = SuccessGreen,
        fontSize = 18.sp,
        lineHeight = 18.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "$completedRunCount completed ${if (completedRunCount == 1) "run" else "runs"}",
        color = SecondaryText,
        fontSize = 13.sp,
        lineHeight = 13.sp,
        maxLines = 1
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun HistoricalRunRow(
    run: HistoricalRun,
    onOpenDetails: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(RowBlack)
            .border(width = 0.5.dp, color = DividerColor)
            .padding(horizontal = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatDateTime(run.completedAtMillis),
                color = PrimaryText,
                fontSize = 14.sp,
                lineHeight = 14.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "${run.splits.size} splits",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                maxLines = 1
            )
        }
        if (run.wasPersonalBest) {
            Text(
                text = "NEW PB",
                color = SuccessGreen,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(58.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = formatSeconds(run.finalTimeMillis),
            color = PrimaryText,
            fontSize = 15.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(74.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        PanelTextButton(
            text = "DETAILS",
            onClick = onOpenDetails,
            modifier = Modifier.size(width = 78.dp, height = 36.dp)
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun HistoricalRunDetailsHeader(
    run: HistoricalRun,
    onBack: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        PanelTextButton(
            text = "BACK",
            onClick = onBack,
            modifier = Modifier.size(width = 72.dp, height = 38.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Run Details",
                color = PrimaryText,
                fontSize = 18.sp,
                lineHeight = 18.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatDateTime(run.completedAtMillis),
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                maxLines = 1
            )
        }
        if (run.wasPersonalBest) {
            Text(
                text = "NEW PB",
                color = SuccessGreen,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = formatSeconds(run.finalTimeMillis),
            color = PrimaryText,
            fontSize = 20.sp,
            lineHeight = 20.sp,
            maxLines = 1
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "${run.gameTitle} - ${run.category}",
        color = SuccessGreen,
        fontSize = 15.sp,
        lineHeight = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(5.dp))
    val segmentDeltas = run.splits.indices.mapNotNull { index ->
        historicalSegmentDeltaMillis(run, index)?.let { delta -> index to delta }
    }
    val largestGain = segmentDeltas.minByOrNull { it.second }?.takeIf { it.second < 0L }
    val largestLoss = segmentDeltas.maxByOrNull { it.second }?.takeIf { it.second > 0L }
    val summaryParts = buildList {
        largestGain?.let { (index, delta) ->
            add("Gain ${run.splits[index].segmentName} ${formatDeltaSeconds(delta)}")
        }
        largestLoss?.let { (index, delta) ->
            add("Loss ${run.splits[index].segmentName} ${formatDeltaSeconds(delta)}")
        }
    }
    Text(
        text = if (summaryParts.isEmpty()) {
            "No previous PB comparison"
        } else {
            summaryParts.joinToString("  |  ")
        },
        color = SecondaryText,
        fontSize = 12.sp,
        lineHeight = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun HistoricalRunDetailsColumnHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(RowBlack)
            .border(width = 0.5.dp, color = DividerColor)
            .padding(horizontal = 10.dp)
    ) {
        HistoricalDetailHeaderText("Split", Modifier.weight(1f), TextAlign.Start)
        HistoricalDetailHeaderText("TIME", Modifier.width(70.dp), TextAlign.End)
        HistoricalDetailHeaderText("SEG", Modifier.width(68.dp), TextAlign.End)
        HistoricalDetailHeaderText("PB Δ", Modifier.width(64.dp), TextAlign.End)
    }
}

@Composable
private fun HistoricalDetailHeaderText(
    text: String,
    modifier: Modifier,
    textAlign: TextAlign
) {
    Text(
        text = text,
        color = SecondaryText,
        fontSize = 11.sp,
        lineHeight = 11.sp,
        maxLines = 1,
        textAlign = textAlign,
        modifier = modifier
    )
}

@Composable
private fun HistoricalRunSplitRow(
    run: HistoricalRun,
    index: Int,
    split: HistoricalSplit
) {
    val segmentDuration = historicalSegmentDurationMillis(run, index)
    val splitDelta = split.comparisonSplitTimeMillis?.let {
        split.splitTimeMillis - it
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(RowBlack)
            .border(width = 0.5.dp, color = DividerColor)
            .padding(horizontal = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight(0.58f)
                .background(if (split.wasGold) GoldSplit else DividerColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${index + 1}. ${split.segmentName}",
            color = PrimaryText,
            fontSize = 13.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatSeconds(split.splitTimeMillis),
            color = PrimaryText,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(70.dp)
        )
        Text(
            text = formatSeconds(segmentDuration),
            color = if (split.wasGold) GoldSplit else PrimaryText,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(68.dp)
        )
        Text(
            text = splitDelta?.let(::formatDeltaSeconds) ?: "--",
            color = when {
                splitDelta == null -> SecondaryText
                splitDelta <= 0L -> SuccessGreen
                else -> BehindRed
            },
            fontSize = 12.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp)
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

private fun historicalSegmentDurationMillis(run: HistoricalRun, index: Int): Long {
    val previousSplitTime = run.splits.getOrNull(index - 1)?.splitTimeMillis ?: 0L
    return (run.splits[index].splitTimeMillis - previousSplitTime).coerceAtLeast(0L)
}

private fun historicalSegmentDeltaMillis(run: HistoricalRun, index: Int): Long? {
    val currentSegmentDuration = historicalSegmentDurationMillis(run, index)
    val comparisonSplitTime = run.splits[index].comparisonSplitTimeMillis ?: return null
    val previousComparisonSplitTime = run.splits
        .getOrNull(index - 1)
        ?.comparisonSplitTimeMillis
        ?: 0L
    return currentSegmentDuration - (comparisonSplitTime - previousComparisonSplitTime)
}

@Composable
private fun BackupHeader(
    selectedPresetCount: Int,
    backupExportState: BackupExportState,
    backupImportState: BackupImportState
) {
    SettingsSectionTitle("Backup / Import")
    Text(
        text = "Back up or restore presets",
        color = PrimaryText,
        fontSize = 18.sp,
        lineHeight = 18.sp,
        maxLines = 1
    )
    Spacer(modifier = Modifier.height(5.dp))
    Text(
        text = "Includes layout, PB, golds, stats, and completed-run history.",
        color = SecondaryText,
        fontSize = 12.sp,
        lineHeight = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(5.dp))
    val statusText = when (backupImportState) {
        BackupImportState.Idle -> when (backupExportState) {
            BackupExportState.Idle -> {
                "$selectedPresetCount selected | Backup format v$BackupSchemaVersion"
            }
            BackupExportState.ChoosingFolder -> "Choose a destination folder..."
            BackupExportState.Exporting -> "Writing backup..."
            BackupExportState.Canceled -> "Backup canceled | $selectedPresetCount selected"
            is BackupExportState.Success -> {
                "Saved ${backupExportState.presetCount} preset(s) | ${backupExportState.fileName}"
            }
            is BackupExportState.Failed -> "Backup failed | ${backupExportState.message}"
        }
        BackupImportState.ChoosingFile -> "Choose a .thorbackup.json file..."
        BackupImportState.Importing -> "Validating and importing backup..."
        BackupImportState.Canceled -> "Import canceled"
        is BackupImportState.Success -> {
            "Imported ${backupImportState.presetCount} preset(s), " +
                "${backupImportState.historyCount} new run(s) | " +
                "Loaded ${backupImportState.activePresetName}"
        }
        is BackupImportState.Failed -> "Import failed | ${backupImportState.message}"
    }
    Text(
        text = statusText,
        color = when {
            backupImportState is BackupImportState.Success -> SuccessGreen
            backupImportState is BackupImportState.Failed -> BehindRed
            backupImportState !is BackupImportState.Idle -> SecondaryText
            backupExportState is BackupExportState.Success -> SuccessGreen
            backupExportState is BackupExportState.Failed -> BehindRed
            else -> SecondaryText
        },
        fontSize = 12.sp,
        lineHeight = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun BackupPresetSelectionRow(
    preset: SplitPreset,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(if (selected) SuccessGreen.copy(alpha = 0.08f) else RowBlack)
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) SuccessGreen else DividerColor
            )
            .padding(horizontal = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .fillMaxHeight(0.62f)
                .background(preset.segments.firstOrNull()?.markerColor ?: SecondaryText)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.presetName,
                color = if (selected) SuccessGreen else PrimaryText,
                fontSize = 14.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${preset.gameTitle} - ${preset.category} | ${preset.segments.size} splits",
                color = SecondaryText,
                fontSize = 11.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        PanelTextButton(
            text = if (selected) "INCLUDED" else "ADD",
            onClick = onToggle,
            enabled = enabled,
            modifier = Modifier.size(width = 84.dp, height = 36.dp)
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun SettingsModeTabs(
    selectedTab: PresetSettingsTab,
    onSelectedTabChange: (PresetSettingsTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .border(width = 1.dp, color = DividerColor)
            .background(RowBlack)
            .padding(4.dp)
    ) {
        SettingsTabButton(
            text = "Create",
            selected = selectedTab == PresetSettingsTab.Create,
            onClick = { onSelectedTabChange(PresetSettingsTab.Create) },
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        SettingsTabButton(
            text = "Edit",
            selected = selectedTab == PresetSettingsTab.Edit,
            onClick = { onSelectedTabChange(PresetSettingsTab.Edit) },
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        SettingsTabButton(
            text = "Records",
            selected = selectedTab == PresetSettingsTab.Records,
            onClick = { onSelectedTabChange(PresetSettingsTab.Records) },
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        SettingsTabButton(
            text = "History",
            selected = selectedTab == PresetSettingsTab.History,
            onClick = { onSelectedTabChange(PresetSettingsTab.History) },
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        SettingsTabButton(
            text = "Backup",
            selected = selectedTab == PresetSettingsTab.Backup,
            onClick = { onSelectedTabChange(PresetSettingsTab.Backup) },
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun UpdateCheckRow(
    updateCheckState: UpdateCheckState,
    onOpenRelease: (String) -> Unit
) {
    val statusText = when (updateCheckState) {
        UpdateCheckState.Idle -> "Preparing update check..."
        UpdateCheckState.Checking -> "Checking updates..."
        is UpdateCheckState.UpToDate -> "Latest version installed | Current v${BuildConfig.VERSION_NAME}"
        is UpdateCheckState.UpdateAvailable -> {
            "Current v${BuildConfig.VERSION_NAME} | Latest ${updateCheckState.latestVersion}"
        }
        UpdateCheckState.Failed -> "Update check failed"
    }
    val updateAvailable = updateCheckState as? UpdateCheckState.UpdateAvailable

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = statusText,
            color = when (updateCheckState) {
                UpdateCheckState.Failed -> BehindRed
                else -> SecondaryText
            },
            fontSize = 13.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (updateAvailable != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Update Now",
                color = LinkBlue,
                fontSize = 13.sp,
                lineHeight = 13.sp,
                fontFamily = FontFamily.Default,
                maxLines = 1,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onOpenRelease(updateAvailable.releaseUrl) }
            )
        }
    }
}

@Composable
private fun ThemeModeToggle(
    selectedThemeMode: AppThemeMode,
    effectiveThemeMode: AppThemeMode,
    useSystemTheme: Boolean,
    onUseSystemThemeChange: (Boolean) -> Unit,
    onSelectedThemeModeChange: (AppThemeMode) -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
        ) {
            Text(
                text = "Use System Setting",
                color = PrimaryText,
                fontSize = 15.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            PanelTextButton(
                text = if (useSystemTheme) "ON" else "OFF",
                onClick = { onUseSystemThemeChange(!useSystemTheme) },
                modifier = Modifier.size(width = 76.dp, height = 34.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .border(width = 1.dp, color = DividerColor)
                .background(RowBlack)
                .padding(4.dp)
        ) {
            AppThemeMode.entries.forEachIndexed { index, themeMode ->
                if (index > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                }
                SettingsTabButton(
                    text = themeMode.label,
                    selected = if (useSystemTheme) {
                        themeMode == effectiveThemeMode
                    } else {
                        selectedThemeMode == themeMode
                    },
                    onClick = { onSelectedThemeModeChange(themeMode) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FontModeToggle(
    selectedFontMode: AppFontMode,
    onSelectedFontModeChange: (AppFontMode) -> Unit
) {
    Column {
        Text(
            text = "Font",
            color = PrimaryText,
            fontSize = 15.sp,
            lineHeight = 15.sp,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(8.dp))
        AppFontMode.entries.chunked(3).forEachIndexed { rowIndex, fontModes ->
            if (rowIndex > 0) {
                Spacer(modifier = Modifier.height(6.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .border(width = 1.dp, color = DividerColor)
                    .background(RowBlack)
                    .padding(4.dp)
            ) {
                fontModes.forEachIndexed { index, fontMode ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    SettingsTabButton(
                        text = fontMode.label,
                        selected = selectedFontMode == fontMode,
                        onClick = { onSelectedFontModeChange(fontMode) },
                        fontFamily = fontMode.fontFamily,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - fontModes.size) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SettingsTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    fontFamily: FontFamily? = null,
    fontSize: TextUnit = 14.sp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val vibrate = rememberButtonVibration()
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> ActiveRowBackground
            selected -> SuccessGreen.copy(alpha = 0.14f)
            else -> RowBlack
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "settingsTabBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) SuccessGreen else DividerColor,
        animationSpec = tween(ButtonFadeMillis),
        label = "settingsTabBorder"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    vibrate()
                    onClick()
                }
            )
            .padding(horizontal = 8.dp)
    ) {
        FadingButtonText(
            text = text,
            color = if (selected) SuccessGreen else PrimaryText,
            fontSize = fontSize,
            fontFamily = fontFamily
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        color = SecondaryText,
        fontSize = 16.sp,
        lineHeight = 16.sp,
        maxLines = 1
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun PresetLoadRow(
    preset: SplitPreset,
    isActive: Boolean,
    isEditing: Boolean,
    canDelete: Boolean,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .border(
                width = 0.75.dp,
                color = when {
                    isEditing -> SuccessGreen
                    isActive -> PrimaryText
                    else -> DividerColor
                }
            )
            .background(if (isActive || isEditing) SuccessGreen.copy(alpha = 0.08f) else RowBlack)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${preset.gameTitle} - ${preset.category}",
                    color = if (isActive) SuccessGreen else PrimaryText,
                    fontSize = 17.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${preset.presetName}  |  ${preset.segments.size} rows",
                    color = SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isActive) {
                Text(
                    text = "LOADED",
                    color = SuccessGreen,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row {
            PanelTextButton(
                text = "LOAD",
                onClick = onLoad,
                modifier = Modifier.size(width = 76.dp, height = 34.dp)
            )
            if (canDelete) {
                Spacer(modifier = Modifier.width(8.dp))
                PanelTextButton(
                    text = "EDIT",
                    onClick = onEdit,
                    modifier = Modifier.size(width = 76.dp, height = 34.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                PanelTextButton(
                    text = "DELETE",
                    onClick = onDelete,
                    modifier = Modifier.size(width = 86.dp, height = 34.dp)
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun LabeledTextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Text(
        text = label,
        color = SecondaryText,
        fontSize = 13.sp,
        lineHeight = 13.sp,
        maxLines = 1
    )
    Spacer(modifier = Modifier.height(4.dp))
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = PrimaryText,
            fontSize = 17.sp,
            lineHeight = 17.sp,
            fontFamily = LocalAppFontFamily.current
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(RowBlack)
            .border(width = 1.dp, color = DividerColor)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun EditableSegmentRow(
    index: Int,
    segment: DraftSplitSegment,
    onNameChange: (String) -> Unit,
    onCycleColor: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(RowBlack)
            .border(width = 0.5.dp, color = DividerColor)
            .padding(horizontal = 10.dp)
    ) {
        Text(
            text = "${index + 1}",
            color = SecondaryText,
            fontSize = 14.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            modifier = Modifier.width(28.dp)
        )
        ColorSwatchButton(
            color = segment.markerColor,
            onClick = onCycleColor,
            modifier = Modifier.size(width = 34.dp, height = 28.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = segment.name,
            onValueChange = onNameChange,
            singleLine = true,
            textStyle = TextStyle(
                color = PrimaryText,
                fontSize = 17.sp,
                lineHeight = 17.sp,
                fontFamily = LocalAppFontFamily.current
            ),
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .background(OledBlack)
                .border(width = 0.5.dp, color = DividerColor)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        PanelIconButton(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = "Move split up",
            onClick = onMoveUp,
            enabled = canMoveUp,
            modifier = Modifier.size(width = 44.dp, height = 30.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        PanelIconButton(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = "Move split down",
            onClick = onMoveDown,
            enabled = canMoveDown,
            modifier = Modifier.size(width = 44.dp, height = 30.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        PanelIconButton(
            imageVector = Icons.Filled.Close,
            contentDescription = "Delete split",
            onClick = onDelete,
            enabled = canDelete,
            modifier = Modifier.size(width = 50.dp, height = 30.dp)
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun ColorSwatchButton(
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val vibrate = rememberButtonVibration()
    val isPressed by interactionSource.collectIsPressedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) PrimaryText else DividerColor,
        animationSpec = tween(ButtonFadeMillis),
        label = "colorSwatchBorder"
    )

    Box(
        modifier = modifier
            .background(color)
            .border(width = 1.5.dp, color = borderColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    vibrate()
                    onClick()
                }
            )
    )
}

@Composable
private fun PanelTextButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val vibrate = rememberButtonVibration()
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> RowBlack
            isPressed -> ActiveRowBackground
            else -> RowBlack
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "panelButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> DividerColor
            isPressed -> PrimaryText
            else -> DividerColor
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "panelButtonBorder"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(backgroundColor)
            .border(width = 1.5.dp, color = borderColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    vibrate()
                    onClick()
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        FadingButtonText(
            text = text,
            color = if (enabled) PrimaryText else SecondaryText,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun DangerPanelTextButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val vibrate = rememberButtonVibration()
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> RowBlack
            isPressed -> BehindRed.copy(alpha = 0.22f)
            else -> BehindRed.copy(alpha = 0.1f)
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "dangerPanelButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (enabled) BehindRed else DividerColor,
        animationSpec = tween(ButtonFadeMillis),
        label = "dangerPanelButtonBorder"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(backgroundColor)
            .border(width = 1.5.dp, color = borderColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    vibrate()
                    onClick()
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        FadingButtonText(
            text = text,
            color = if (enabled) BehindRed else SecondaryText,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun PanelIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val vibrate = rememberButtonVibration()
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> RowBlack
            isPressed -> ActiveRowBackground
            else -> RowBlack
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "panelIconButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> DividerColor
            isPressed -> PrimaryText
            else -> DividerColor
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "panelIconButtonBorder"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(backgroundColor)
            .border(width = 1.5.dp, color = borderColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    vibrate()
                    onClick()
                }
            )
            .padding(4.dp)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) PrimaryText else SecondaryText,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val vibrate = rememberButtonVibration()
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) ActiveRowBackground else RowBlack,
        animationSpec = tween(ButtonFadeMillis),
        label = "closeButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) SuccessGreen else PrimaryText,
        animationSpec = tween(ButtonFadeMillis),
        label = "closeButtonBorder"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(backgroundColor)
            .border(width = 1.5.dp, color = borderColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    vibrate()
                    onClick()
                }
            )
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Close settings",
            tint = PrimaryText,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun SplitButton(
    enabled: Boolean,
    text: String,
    onSplit: () -> Unit,
    fontSize: TextUnit = 22.sp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val vibrate = rememberButtonVibration()
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDoneState = text == "DONE"
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> RowBlack
            isPressed -> ActiveRowBackground
            isDoneState -> SuccessGreen.copy(alpha = 0.12f)
            else -> RowBlack
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "splitButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> DividerColor
            isPressed -> PrimaryText
            isDoneState -> SuccessGreen
            else -> PrimaryText
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "splitButtonBorder"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            !enabled -> SecondaryText
            isDoneState -> SuccessGreen
            else -> PrimaryText
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "splitButtonTextColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(backgroundColor)
            .border(width = 2.dp, color = borderColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    vibrate()
                    onSplit()
                }
            )
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        FadingButtonText(
            text = text,
            color = textColor,
            fontSize = fontSize
        )
    }
}

@Composable
private fun FadingButtonText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily? = null,
    modifier: Modifier = Modifier
) {
    Crossfade(
        targetState = text,
        animationSpec = tween(ButtonFadeMillis),
        label = "buttonTextFade",
        modifier = modifier
    ) { displayedText ->
        Text(
            text = displayedText,
            color = color,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontFamily = fontFamily,
            maxLines = 1
        )
    }
}

private fun formatSeconds(milliseconds: Long): String {
    return formatTimeValue(milliseconds)
}

private fun formatDeltaSeconds(milliseconds: Long): String {
    val sign = if (milliseconds >= 0L) "+" else "-"
    return "$sign${formatTimeValue(kotlin.math.abs(milliseconds))}"
}

private suspend fun fetchLatestGithubRelease(): GithubRelease = withContext(Dispatchers.IO) {
    val connection = (URL(LatestReleaseApiUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "ThorSpeedrunSplits/${BuildConfig.VERSION_NAME}")
    }

    try {
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        GithubRelease(
            tagName = json.getString("tag_name"),
            htmlUrl = json.getString("html_url")
        )
    } finally {
        connection.disconnect()
    }
}

private fun isRemoteVersionNewer(currentVersion: String, remoteVersion: String): Boolean {
    val currentParts = versionParts(currentVersion)
    val remoteParts = versionParts(remoteVersion)
    val maxSize = maxOf(currentParts.size, remoteParts.size)

    for (index in 0 until maxSize) {
        val currentPart = currentParts.getOrElse(index) { 0 }
        val remotePart = remoteParts.getOrElse(index) { 0 }
        if (remotePart != currentPart) {
            return remotePart > currentPart
        }
    }

    return false
}

private fun versionParts(version: String): List<Int> {
    return version
        .removePrefix("v")
        .removePrefix("V")
        .split(".", "-", "_")
        .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
}

private fun liveActiveSplitDeltaMillis(
    elapsedMillis: Long,
    activeSplitIndex: Int,
    completedTimes: List<Long?>,
    runComparison: Run?
): Long? {
    val comparisonTime = runComparison?.splitTimes?.getOrNull(activeSplitIndex) ?: return null
    val currentDelta = elapsedMillis - comparisonTime
    if (currentDelta > 0L) {
        return currentDelta
    }

    val segmentStartDelta = if (activeSplitIndex == 0) {
        0L
    } else {
        val previousCompletedTime = completedTimes.getOrNull(activeSplitIndex - 1) ?: return null
        val previousComparisonTime = runComparison.splitTimes.getOrNull(activeSplitIndex - 1) ?: return null
        previousCompletedTime - previousComparisonTime
    }

    return currentDelta.takeIf { it > segmentStartDelta }
}

private fun formatDuration(milliseconds: Long): String {
    return formatTimeValue(milliseconds)
}

private fun formatDateTime(milliseconds: Long): String {
    return SimpleDateFormat("MM/dd/yy hh:mma", Locale.US)
        .format(Date(milliseconds))
        .lowercase(Locale.US)
}

private fun formatTimeValue(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000.0
    val hours = (totalSeconds / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> {
            String.format(Locale.US, "%d:%02d:%04.1f", hours, minutes, seconds)
        }
        minutes > 0 -> {
            String.format(Locale.US, "%d:%04.1f", minutes, seconds)
        }
        else -> {
            String.format(Locale.US, "%.1f", seconds)
        }
    }
}

@Preview(showBackground = true, widthDp = 620, heightDp = 540)
@Composable
private fun ThorSpeedrunSplitsPreview() {
    ThorSpeedrunSplitsTheme(dynamicColor = false, darkTheme = true) {
        ThorSpeedrunSplitsApp()
    }
}
