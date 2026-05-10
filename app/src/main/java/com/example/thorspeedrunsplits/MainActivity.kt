package com.example.thorspeedrunsplits

import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.thorspeedrunsplits.ui.theme.ThorSpeedrunSplitsTheme
import java.util.Locale
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
    val splitTimes: List<Long>,
    val finalTime: Long
)

private fun Run.toPersonalBestRunEntity(): PersonalBestRunEntity {
    return PersonalBestRunEntity(
        presetName = presetName,
        splitTimesMillis = splitTimes,
        finalTimeMillis = finalTime,
        updatedAtMillis = System.currentTimeMillis()
    )
}

private fun PersonalBestRunEntity.toRun(): Run {
    return Run(
        presetName = presetName,
        splitTimes = splitTimesMillis,
        finalTime = finalTimeMillis
    )
}

private val PresetColors = listOf(
    Color(0xFFFF6A2A),
    Color(0xFFFFB13B),
    Color(0xFF3B70FF),
    Color(0xFF25D8A0),
    Color(0xFFB76DFF),
    Color(0xFFFF4FA3),
    Color(0xFF37D5FF),
    Color(0xFFE6D84A),
    Color(0xFF8BE36E),
    Color(0xFFFF8E3D)
)

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

private val OledBlack = Color(0xFF020202)
private val RowBlack = Color(0xFF080808)
private val DividerColor = Color(0xFF242424)
private val PrimaryText = Color(0xFFF6F6F6)
private val SecondaryText = Color(0xFFC8C8C8)
private val SuccessGreen = Color(0xFF65E38F)
private val BehindRed = Color(0xFFFF7070)
private const val ButtonFadeMillis = 280

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
    val personalBestRunDao = remember {
        ThorSpeedrunDatabase.getInstance(appContext).personalBestRunDao()
    }
    val coroutineScope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }
    var activeSplitIndex by remember { mutableStateOf(0) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var activePreset by remember { mutableStateOf(DefaultPreset) }
    val savedPresets = remember {
        mutableStateListOf<SplitPreset>().apply { add(DefaultPreset) }
    }
    val savedRuns = remember { mutableStateMapOf<String, Run>() }
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
    var startedAtMillis by remember { mutableLongStateOf(0L) }
    var finishedElapsedMillis by remember { mutableLongStateOf(0L) }
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var runComparison by remember { mutableStateOf<Run?>(null) }
    val completedTimes = remember {
        mutableStateListOf<Long?>().apply {
            repeat(DefaultPreset.segments.size) { add(null) }
        }
    }

    LaunchedEffect(personalBestRunDao) {
        savedRuns.clear()
        personalBestRunDao.getAll().forEach { savedRun ->
            savedRuns[savedRun.presetName] = savedRun.toRun()
        }
    }

    fun resetRun(segmentCount: Int) {
        isRunning = false
        isFinished = false
        activeSplitIndex = 0
        startedAtMillis = 0L
        finishedElapsedMillis = 0L
        nowMillis = SystemClock.elapsedRealtime()
        runComparison = null
        completedTimes.clear()
        repeat(segmentCount) { completedTimes.add(null) }
    }

    fun loadPreset(preset: SplitPreset) {
        activePreset = preset
        resetRun(preset.segments.size)
        isSettingsOpen = false
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            nowMillis = SystemClock.elapsedRealtime()
            delay(33L)
        }
    }

    val elapsedMillis = when {
        isRunning -> nowMillis - startedAtMillis
        isFinished -> finishedElapsedMillis
        else -> 0L
    }
    val savedRunForActivePreset = savedRuns[activePreset.presetName]
        ?.takeIf { it.splitTimes.size == activePreset.segments.size }
    val displayedComparisonRun = runComparison ?: savedRunForActivePreset

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
            val splitButtonSize = if (isWideThorShape) {
                ButtonSize(width = 220.dp, height = 104.dp)
            } else {
                ButtonSize(width = 210.dp, height = 120.dp)
            }

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
                    activeSplitIndex = activeSplitIndex,
                    isFinished = isFinished,
                    rowHeight = rowHeight,
                    rowTextSize = rowTextSize,
                    modifier = Modifier.weight(1f)
                )
                BottomControls(
                    buttonEnabled = true,
                    buttonText = if (isFinished) "DONE" else "SPLIT",
                    buttonSize = splitButtonSize,
                    timerText = formatSeconds(elapsedMillis),
                    timerColor = if (isFinished) SuccessGreen else PrimaryText,
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
                            runComparison = savedRunForActivePreset
                            isRunning = true
                            startedAtMillis = pressTime
                            nowMillis = pressTime
                            return@BottomControls
                        }

                        val splitElapsed = pressTime - startedAtMillis
                        completedTimes[activeSplitIndex] = splitElapsed

                        if (activeSplitIndex == activePreset.segments.lastIndex) {
                            isRunning = false
                            isFinished = true
                            finishedElapsedMillis = splitElapsed
                            nowMillis = pressTime
                            val completedRun = Run(
                                presetName = activePreset.presetName,
                                splitTimes = completedTimes.mapIndexed { index, time ->
                                    when {
                                        index == activeSplitIndex -> splitElapsed
                                        time != null -> time
                                        else -> splitElapsed
                                    }
                                },
                                finalTime = splitElapsed
                            )
                            val currentBest = savedRuns[activePreset.presetName]
                            if (
                                currentBest == null ||
                                currentBest.splitTimes.size != completedRun.splitTimes.size ||
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
                    onClose = { isSettingsOpen = false },
                    savedPresets = savedPresets,
                    activePreset = activePreset,
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
                        val nextColor = PresetColors[
                            (PresetColors.indexOf(currentColor).takeIf { it >= 0 } ?: 0)
                                .plus(1)
                                .mod(PresetColors.size)
                        ]
                        draftSegments[index] = draftSegments[index].copy(markerColor = nextColor)
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
                    onRemoveDraftSegment = {
                        if (draftSegments.size > 1) {
                            draftSegments.removeAt(draftSegments.lastIndex)
                        }
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
                                coroutineScope.launch {
                                    personalBestRunDao.deleteByPresetName(preset.presetName)
                                }
                            }
                            savedPresets[existingIndex] = preset
                        } else {
                            savedPresets.add(preset)
                        }
                        loadPreset(preset)
                    },
                    onLoadPreset = ::loadPreset,
                    onDeletePreset = { preset ->
                        if (preset.presetName != DefaultPreset.presetName) {
                            val deletedActivePreset = preset.presetName == activePreset.presetName
                            val deleteIndex = savedPresets.indexOfFirst {
                                it.presetName == preset.presetName
                            }
                            if (deleteIndex >= 0) {
                                savedPresets.removeAt(deleteIndex)
                                savedRuns.remove(preset.presetName)
                                coroutineScope.launch {
                                    personalBestRunDao.deleteByPresetName(preset.presetName)
                                }
                            }
                            if (deletedActivePreset) {
                                activePreset = DefaultPreset
                                resetRun(DefaultPreset.segments.size)
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
                        .padding(horizontal = 36.dp, vertical = 32.dp)
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

private data class ButtonSize(
    val width: Dp,
    val height: Dp
)

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
private fun SplitList(
    splits: List<SplitSegment>,
    completedTimes: List<Long?>,
    displayedComparisonRun: Run?,
    runComparison: Run?,
    activeSplitIndex: Int,
    isFinished: Boolean,
    rowHeight: Dp,
    rowTextSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val oneAwayVisibleIndex = when {
        splits.isEmpty() -> 0
        isFinished -> activeSplitIndex.coerceIn(splits.indices)
        activeSplitIndex < splits.lastIndex -> activeSplitIndex + 1
        else -> activeSplitIndex
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
            val deltaMillis = completedTimes[index]?.let { completedTime ->
                runComparison?.splitTimes?.getOrNull(index)?.let { comparisonTime ->
                    completedTime - comparisonTime
                }
            }
            SplitRow(
                split = split,
                comparisonTime = personalBestTime?.let(::formatSeconds) ?: "--",
                hasComparisonTime = personalBestTime != null,
                deltaText = deltaMillis?.let(::formatDeltaSeconds),
                deltaColor = deltaMillis?.let {
                    if (it <= 0L) SuccessGreen else BehindRed
                } ?: SecondaryText,
                isActive = index == activeSplitIndex && !isFinished,
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
    deltaText: String?,
    deltaColor: Color,
    isActive: Boolean,
    rowHeight: Dp,
    textSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val rowBackground = if (isActive) Color(0xFF111111) else RowBlack
    val timeColor = if (hasComparisonTime) PrimaryText else SecondaryText

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
    timerText: String,
    timerColor: Color,
    timerSize: TextUnit,
    onSplit: () -> Unit,
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
            modifier = Modifier.size(width = buttonSize.width, height = buttonSize.height)
        )
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
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) Color(0xFF2A2A2A) else Color(0xFF101010),
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
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        GearIcon(
            color = PrimaryText,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun GearIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.11f
        val center = this.center
        val outerRadius = size.minDimension * 0.32f
        val innerRadius = size.minDimension * 0.12f
        val toothStart = size.minDimension * 0.38f
        val toothEnd = size.minDimension * 0.48f

        repeat(6) { tooth ->
            val angleRadians = Math.toRadians((tooth * 60).toDouble())
            val start = androidx.compose.ui.geometry.Offset(
                x = center.x + kotlin.math.cos(angleRadians).toFloat() * toothStart,
                y = center.y + kotlin.math.sin(angleRadians).toFloat() * toothStart
            )
            val end = androidx.compose.ui.geometry.Offset(
                x = center.x + kotlin.math.cos(angleRadians).toFloat() * toothEnd,
                y = center.y + kotlin.math.sin(angleRadians).toFloat() * toothEnd
            )
            drawLine(
                color = color,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }

        drawCircle(
            color = color,
            radius = outerRadius,
            style = Stroke(width = strokeWidth)
        )
        drawCircle(
            color = color,
            radius = innerRadius,
            style = Stroke(width = strokeWidth)
        )
    }
}

@Composable
private fun SettingsPanel(
    onClose: () -> Unit,
    savedPresets: List<SplitPreset>,
    activePreset: SplitPreset,
    draftPresetName: String,
    onDraftPresetNameChange: (String) -> Unit,
    draftGameTitle: String,
    onDraftGameTitleChange: (String) -> Unit,
    draftCategory: String,
    onDraftCategoryChange: (String) -> Unit,
    draftSegments: List<DraftSplitSegment>,
    onDraftSegmentNameChange: (Int, String) -> Unit,
    onCycleDraftSegmentColor: (Int) -> Unit,
    onAddDraftSegment: () -> Unit,
    onRemoveDraftSegment: () -> Unit,
    onSaveDraftPreset: () -> Unit,
    onLoadPreset: (SplitPreset) -> Unit,
    onDeletePreset: (SplitPreset) -> Unit,
    onResetDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF080808))
            .border(width = 1.dp, color = DividerColor)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, top = 22.dp, end = 94.dp, bottom = 22.dp)
        ) {
            item {
                Text(
                    text = "Split Presets",
                    color = PrimaryText,
                    fontSize = 24.sp,
                    lineHeight = 24.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(18.dp))
            }

            item {
                SettingsSectionTitle("Load Preset")
                savedPresets.forEach { preset ->
                    PresetLoadRow(
                        preset = preset,
                        isActive = preset.presetName == activePreset.presetName,
                        canDelete = preset.presetName != DefaultPreset.presetName,
                        onLoad = { onLoadPreset(preset) },
                        onDelete = { onDeletePreset(preset) }
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
            }

            item {
                SettingsSectionTitle("Create Preset")
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
                key = { _, segment -> segment.id }
            ) { index, segment ->
                DraftSegmentRow(
                    index = index,
                    segment = segment,
                    onNameChange = { onDraftSegmentNameChange(index, it) },
                    onCycleColor = { onCycleDraftSegmentColor(index) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    PanelTextButton(
                        text = "+ ROW",
                        onClick = onAddDraftSegment,
                        modifier = Modifier.size(width = 112.dp, height = 46.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    PanelTextButton(
                        text = "- ROW",
                        onClick = onRemoveDraftSegment,
                        modifier = Modifier.size(width = 112.dp, height = 46.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    PanelTextButton(
                        text = "SAVE + LOAD",
                        onClick = onSaveDraftPreset,
                        modifier = Modifier.size(width = 172.dp, height = 46.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    PanelTextButton(
                        text = "RESET DEFAULT",
                        onClick = onResetDefault,
                        modifier = Modifier.size(width = 190.dp, height = 46.dp)
                    )
                }
            }
        }

        CloseButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(56.dp)
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
    canDelete: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .border(width = 0.5.dp, color = if (isActive) SuccessGreen else DividerColor)
            .background(if (isActive) Color(0xFF0D170D) else Color(0xFF0A0A0A))
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = "${preset.gameTitle} - ${preset.category}",
            color = if (isActive) SuccessGreen else PrimaryText,
            fontSize = 17.sp,
            lineHeight = 17.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${preset.segments.size} rows",
            color = SecondaryText,
            fontSize = 14.sp,
            lineHeight = 14.sp,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(12.dp))
        PanelTextButton(
            text = "LOAD",
            onClick = onLoad,
            modifier = Modifier.size(width = 86.dp, height = 34.dp)
        )
        if (canDelete) {
            Spacer(modifier = Modifier.width(8.dp))
            PanelTextButton(
                text = "DELETE",
                onClick = onDelete,
                modifier = Modifier.size(width = 104.dp, height = 34.dp)
            )
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
            lineHeight = 17.sp
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF0D0D0D))
            .border(width = 1.dp, color = DividerColor)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun DraftSegmentRow(
    index: Int,
    segment: DraftSplitSegment,
    onNameChange: (String) -> Unit,
    onCycleColor: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Color(0xFF0A0A0A))
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
                lineHeight = 17.sp
            ),
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .background(Color(0xFF050505))
                .border(width = 0.5.dp, color = DividerColor)
                .padding(horizontal = 8.dp, vertical = 8.dp)
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
                onClick = onClick
            )
    )
}

@Composable
private fun PanelTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) Color(0xFF2A2A2A) else Color(0xFF101010),
        animationSpec = tween(ButtonFadeMillis),
        label = "panelButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) PrimaryText else DividerColor,
        animationSpec = tween(ButtonFadeMillis),
        label = "panelButtonBorder"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(backgroundColor)
            .border(width = 1.5.dp, color = borderColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        FadingButtonText(
            text = text,
            color = PrimaryText,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) Color(0xFF2A2A2A) else Color(0xFF101010),
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
                onClick = onClick
            )
    ) {
        FadingButtonText(
            text = "X",
            color = PrimaryText,
            fontSize = 24.sp
        )
    }
}

@Composable
private fun SplitButton(
    enabled: Boolean,
    text: String,
    onSplit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDoneState = text == "DONE"
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFF070707)
            isPressed -> Color(0xFF303030)
            isDoneState -> Color(0xFF172016)
            else -> Color(0xFF121212)
        },
        animationSpec = tween(ButtonFadeMillis),
        label = "splitButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFF333333)
            isPressed -> PrimaryText
            isDoneState -> SuccessGreen
            else -> Color(0xFFDEDEDE)
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
            .widthIn(min = 180.dp)
            .background(backgroundColor)
            .border(width = 2.dp, color = borderColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSplit
            )
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        FadingButtonText(
            text = text,
            color = textColor,
            fontSize = 28.sp
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
    return String.format(Locale.US, "%.1fs", milliseconds / 1000.0)
}

private fun formatDeltaSeconds(milliseconds: Long): String {
    val sign = if (milliseconds >= 0L) "+" else "-"
    return String.format(Locale.US, "%s%.1fs", sign, kotlin.math.abs(milliseconds) / 1000.0)
}

@Preview(showBackground = true, widthDp = 620, heightDp = 540)
@Composable
private fun ThorSpeedrunSplitsPreview() {
    ThorSpeedrunSplitsTheme(dynamicColor = false, darkTheme = true) {
        ThorSpeedrunSplitsApp()
    }
}
