package com.example.thorspeedrunsplits

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
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

private data class SplitSegment(
    val name: String,
    val targetSeconds: Double,
    val markerColor: Color
)

private val ExampleSplits = listOf(
    SplitSegment("One", 1.0, Color(0xFFFF6A2A)),
    SplitSegment("Two", 2.0, Color(0xFFFFB13B)),
    SplitSegment("Three", 3.0, Color(0xFF3B70FF)),
    SplitSegment("Four", 4.0, Color(0xFF25D8A0))
)

private val OledBlack = Color(0xFF020202)
private val RowBlack = Color(0xFF080808)
private val DividerColor = Color(0xFF242424)
private val PrimaryText = Color(0xFFF6F6F6)
private val SecondaryText = Color(0xFFC8C8C8)
private val SuccessGreen = Color(0xFF65E38F)

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
    var isRunning by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }
    var activeSplitIndex by remember { mutableStateOf(0) }
    var startedAtMillis by remember { mutableLongStateOf(0L) }
    var finishedElapsedMillis by remember { mutableLongStateOf(0L) }
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val completedTimes = remember {
        mutableStateListOf<Long?>().apply {
            repeat(ExampleSplits.size) { add(null) }
        }
    }
    val inspectionMode = LocalInspectionMode.current

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp)
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
                    game = "Game",
                    category = "Any%",
                    fontSize = titleSize,
                    modifier = Modifier.height(titleHeight)
                )
                SplitList(
                    splits = ExampleSplits,
                    completedTimes = completedTimes,
                    activeSplitIndex = activeSplitIndex,
                    isFinished = isFinished,
                    rowHeight = rowHeight,
                    rowTextSize = rowTextSize
                )
                Spacer(modifier = Modifier.weight(1f))
                BottomControls(
                    buttonEnabled = !isFinished || inspectionMode,
                    buttonSize = splitButtonSize,
                    timerText = formatSeconds(elapsedMillis),
                    timerColor = if (isFinished) SuccessGreen else PrimaryText,
                    timerSize = timerSize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomHeight),
                    onSplit = {
                        if (isFinished) return@BottomControls

                        val pressTime = SystemClock.elapsedRealtime()
                        if (!isRunning) {
                            isRunning = true
                            startedAtMillis = pressTime
                            nowMillis = pressTime
                            return@BottomControls
                        }

                        val splitElapsed = pressTime - startedAtMillis
                        completedTimes[activeSplitIndex] = splitElapsed

                        if (activeSplitIndex == ExampleSplits.lastIndex) {
                            isRunning = false
                            isFinished = true
                            finishedElapsedMillis = splitElapsed
                            nowMillis = pressTime
                        } else {
                            activeSplitIndex += 1
                            nowMillis = pressTime
                        }
                    }
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
    activeSplitIndex: Int,
    isFinished: Boolean,
    rowHeight: Dp,
    rowTextSize: TextUnit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        splits.forEachIndexed { index, split ->
            SplitRow(
                split = split,
                displayTime = completedTimes[index]?.let(::formatSeconds)
                    ?: formatTargetSeconds(split.targetSeconds),
                isActive = index == activeSplitIndex && !isFinished,
                isCompleted = completedTimes[index] != null,
                rowHeight = rowHeight,
                textSize = rowTextSize
            )
        }
    }
}

@Composable
private fun SplitRow(
    split: SplitSegment,
    displayTime: String,
    isActive: Boolean,
    isCompleted: Boolean,
    rowHeight: Dp,
    textSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val rowBackground = if (isActive) Color(0xFF111111) else RowBlack
    val timeColor = when {
        isCompleted -> SuccessGreen
        else -> PrimaryText
    }

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
        Text(
            text = displayTime,
            color = timeColor,
            fontSize = textSize,
            lineHeight = textSize,
            maxLines = 1
        )
    }
}

@Composable
private fun BottomControls(
    buttonEnabled: Boolean,
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
private fun SplitButton(
    enabled: Boolean,
    onSplit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .widthIn(min = 180.dp)
            .background(if (enabled) Color(0xFF121212) else Color(0xFF070707))
            .border(width = 2.dp, color = if (enabled) Color(0xFFDEDEDE) else Color(0xFF333333))
            .clickable(enabled = enabled, onClick = onSplit)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = if (enabled) "SPLIT" else "DONE",
            color = if (enabled) PrimaryText else SecondaryText,
            fontSize = 28.sp,
            lineHeight = 28.sp,
            maxLines = 1
        )
    }
}

private fun formatSeconds(milliseconds: Long): String {
    return String.format(Locale.US, "%.1fs", milliseconds / 1000.0)
}

private fun formatTargetSeconds(seconds: Double): String {
    return String.format(Locale.US, "%.1fs", seconds)
}

@Preview(showBackground = true, widthDp = 620, heightDp = 540)
@Composable
private fun ThorSpeedrunSplitsPreview() {
    ThorSpeedrunSplitsTheme(dynamicColor = false, darkTheme = true) {
        ThorSpeedrunSplitsApp()
    }
}
