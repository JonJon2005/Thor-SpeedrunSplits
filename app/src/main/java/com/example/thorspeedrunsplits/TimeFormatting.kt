package com.example.thorspeedrunsplits

internal fun formatTimeValue(milliseconds: Long, decimals: Int): String {
    // Truncate at the selected precision so an unfinished second never rounds up.
    val elapsed = milliseconds.coerceAtLeast(0L)
    val totalSeconds = elapsed / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    val fraction = when (decimals) {
        1 -> ".${elapsed % 1000L / 100L}"
        2 -> ".${(elapsed % 1000L / 10L).toString().padStart(2, '0')}"
        else -> ""
    }
    val secondsText = seconds.toString().padStart(2, '0') + fraction
    return when {
        hours > 0L -> "$hours:${minutes.toString().padStart(2, '0')}:$secondsText"
        minutes > 0L -> "$minutes:$secondsText"
        else -> "$seconds$fraction"
    }
}
