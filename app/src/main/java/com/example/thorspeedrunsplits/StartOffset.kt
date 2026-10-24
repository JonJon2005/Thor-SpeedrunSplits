package com.example.thorspeedrunsplits

internal const val MaxStartCountdownSeconds = 3600

internal fun parseStartCountdownSeconds(value: String): Int? =
    value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in 0..MaxStartCountdownSeconds }

internal fun formatRunTimer(milliseconds: Long, decimals: Int): String {
    if (milliseconds >= 0L) return formatTimeValue(milliseconds, decimals)
    // Round remaining countdown time up so it never displays negative zero.
    val unit = when (decimals) { 0 -> 1000L; 1 -> 100L; else -> 10L }
    val remaining = -milliseconds
    val rounded = ((remaining - 1L) / unit + 1L) * unit
    return "-${formatTimeValue(rounded, decimals)}"
}
