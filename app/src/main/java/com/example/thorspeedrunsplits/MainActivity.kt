package com.example.thorspeedrunsplits

import android.content.Context
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
import com.example.thorspeedrunsplits.ui.theme.ThorSpeedrunSplitsTheme
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

private data class BestSegments(
    val presetName: String,
    val segmentTimes: List<Long?>
)

private data class PresetStats(
    val attemptedRuns: Int = 0,
    val totalTimeMillis: Long = 0L
)

private const val LoadedPresetPreferenceKey = "loaded_preset_name"
private const val ThemePreferenceKey = "theme_mode"
private const val UseSystemThemePreferenceKey = "use_system_theme"
private const val FontPreferenceKey = "font_mode"
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
    var presetSettingsTab by remember { mutableStateOf(PresetSettingsTab.Create) }
    val savedPresets = remember {
        mutableStateListOf<SplitPreset>().apply { add(DefaultPreset) }
    }
    val savedRuns = remember { mutableStateMapOf<String, Run>() }
    val savedBestSegments = remember { mutableStateMapOf<String, BestSegments>() }
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

    LaunchedEffect(personalBestRunDao, bestSegmentsDao, splitPresetDao, appPreferenceDao) {
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
            val bottomButtonSide = if (isWideThorShape) 100.dp else 112.dp
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
                        .padding(horizontal = 24.dp)
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
                            if (
                                currentBest == null ||
                                currentBest.splitTimes.size != completedRun.splitTimes.size ||
                                currentBest.splitTimes.any { it == null } ||
                                completedRun.finalTime < currentBest.finalTime
                            ) {
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
                    }
                )
            }

            PresetStatsPanel(
                attemptedRuns = activePresetStats.attemptedRuns,
                totalTimeText = formatDuration(displayedTotalTimeMillis),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 12.dp, start = 24.dp)
            )

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
                    onClose = { isSettingsOpen = false },
                    savedPresets = savedPresets,
                    activePreset = activePreset,
                    activePersonalBest = savedRuns[activePreset.presetName],
                    activeBestSegments = savedBestSegments[activePreset.presetName],
                    selectedThemeMode = selectedThemeMode,
                    effectiveThemeMode = effectiveThemeMode,
                    useSystemTheme = useSystemTheme,
                    selectedFontMode = selectedFontMode,
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
                    selectedTab = presetSettingsTab,
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
                                    splitPresetDao.deleteByPresetName(preset.presetName)
                                }
                                if (editTargetPresetName == preset.presetName) {
                                    editTargetPresetName = null
                                    editSegments.clear()
                                }
                            }
                            if (deletedActivePreset) {
                                loadPreset(DefaultPreset)
                            }
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
    Records
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = game,
            color = PrimaryText,
            fontSize = fontSize,
            lineHeight = fontSize,
            maxLines = 1
        )
        Text(
            text = category,
            color = PrimaryText,
            fontSize = fontSize,
            lineHeight = fontSize,
            maxLines = 1
        )
    }
}

@Composable
private fun PresetStatsPanel(
    attemptedRuns: Int,
    totalTimeText: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
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
                modifier = Modifier.width(76.dp)
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
            modifier = Modifier.width(72.dp)
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
    timerText: String,
    timerColor: Color,
    timerSize: TextUnit,
    onSplit: () -> Unit,
    onReset: () -> Unit,
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
            visible = showResetButton,
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
                SplitButton(
                    enabled = true,
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
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = timerText,
            color = timerColor,
            fontSize = timerSize,
            lineHeight = timerSize,
            maxLines = 1
        )
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
    selectedThemeMode: AppThemeMode,
    effectiveThemeMode: AppThemeMode,
    useSystemTheme: Boolean,
    selectedFontMode: AppFontMode,
    onSelectedThemeModeChange: (AppThemeMode) -> Unit,
    onUseSystemThemeChange: (Boolean) -> Unit,
    onSelectedFontModeChange: (AppFontMode) -> Unit,
    selectedTab: PresetSettingsTab,
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
    Column(
        modifier = modifier
            .background(OledBlack)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(RowBlack)
                .border(width = 0.5.dp, color = DividerColor)
                .padding(start = 24.dp, end = 14.dp)
        ) {
            Text(
                text = "Split Presets",
                color = PrimaryText,
                fontSize = 24.sp,
                lineHeight = 24.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.weight(1f))
            CloseButton(
                onClick = onClose,
                modifier = Modifier.size(52.dp)
            )
        }

        LazyColumn(
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
                        onClearPersonalBest = { onClearPersonalBest(preset) },
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
                            text = "+ ROW",
                            onClick = onAddDraftSegment,
                            modifier = Modifier.size(width = 96.dp, height = 40.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        PanelTextButton(
                            text = "SAVE + LOAD",
                            onClick = onSaveDraftPreset,
                            modifier = Modifier.size(width = 138.dp, height = 40.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        PanelTextButton(
                            text = "RESET DEFAULT",
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
                                text = "+ ROW",
                                onClick = onAddEditSegment,
                                modifier = Modifier.size(width = 96.dp, height = 40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            PanelTextButton(
                                text = "SAVE EDIT",
                                onClick = onSaveEditedPreset,
                                modifier = Modifier.size(width = 126.dp, height = 40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            } else {
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
            text = "CLEAR GOLD",
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
        PanelTextButton(
            text = "X",
            onClick = onClearBestSegment,
            enabled = bestSegmentTime != null,
            modifier = Modifier.size(width = 66.dp, height = 30.dp)
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
            text = "Create Preset",
            selected = selectedTab == PresetSettingsTab.Create,
            onClick = { onSelectedTabChange(PresetSettingsTab.Create) },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        SettingsTabButton(
            text = "Edit Preset",
            selected = selectedTab == PresetSettingsTab.Edit,
            onClick = { onSelectedTabChange(PresetSettingsTab.Edit) },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        SettingsTabButton(
            text = "Records",
            selected = selectedTab == PresetSettingsTab.Records,
            onClick = { onSelectedTabChange(PresetSettingsTab.Records) },
            modifier = Modifier.weight(1f)
        )
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
            fontSize = 14.sp
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
    onClearPersonalBest: () -> Unit,
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
            Spacer(modifier = Modifier.width(8.dp))
            PanelTextButton(
                text = "CLR PB",
                onClick = onClearPersonalBest,
                modifier = Modifier.size(width = 86.dp, height = 34.dp)
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
                    text = "DEL",
                    onClick = onDelete,
                    modifier = Modifier.size(width = 64.dp, height = 34.dp)
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
        PanelTextButton(
            text = "X",
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
