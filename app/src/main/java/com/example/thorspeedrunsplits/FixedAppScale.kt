package com.example.thorspeedrunsplits

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Baseline measured on the real Thor bottom display: 1240 x 1080, 369 logical DPI.
// Use raw window constraints, not system dp dimensions, to ignore display-size settings.
internal fun fixedAppDensity(widthPixels: Int, heightPixels: Int): Float {
    require(widthPixels > 0 && heightPixels > 0)
    val longSide = maxOf(widthPixels, heightPixels).toFloat()
    val shortSide = minOf(widthPixels, heightPixels).toFloat()
    return (369f / 160f) * minOf(longSide / 1240f, shortSide / 1080f)
}

@Composable
internal fun FixedAppScale(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Skip transient zero-size windows rather than supplying an invalid density.
        if (constraints.maxWidth > 0 && constraints.maxHeight > 0) {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = fixedAppDensity(constraints.maxWidth, constraints.maxHeight),
                    fontScale = 1f
                ),
                content = content
            )
        }
    }
}
