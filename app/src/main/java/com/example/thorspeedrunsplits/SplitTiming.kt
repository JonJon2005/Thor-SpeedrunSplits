package com.example.thorspeedrunsplits

// Null means that the boundary was not timed. A combined segment is not an individual gold.
internal fun isolatedSegmentDuration(times: List<Long?>, index: Int): Long? {
    val end = times.getOrNull(index) ?: return null
    val start = if (index == 0) 0L else times.getOrNull(index - 1) ?: return null
    return (end - start).coerceAtLeast(0L)
}

internal fun lastTimedSplitBefore(times: List<Long?>, index: Int): Long =
    times.take(index).lastOrNull { it != null } ?: 0L

internal fun liveSplitDelta(
    elapsed: Long,
    index: Int,
    completed: List<Long?>,
    comparison: List<Long?>?
): Long? {
    val target = comparison?.getOrNull(index) ?: return null
    val delta = elapsed - target
    if (delta > 0L) return delta
    val sharedIndex = (index - 1 downTo 0).firstOrNull {
        completed.getOrNull(it) != null && comparison.getOrNull(it) != null
    }
    val startDelta = sharedIndex?.let { completed[it]!! - comparison[it]!! } ?: 0L
    return delta.takeIf { it > startDelta }
}

internal fun shouldReplacePersonalBest(
    candidateTime: Long,
    candidateSplits: List<Long?>,
    currentTime: Long,
    currentSplits: List<Long?>
): Boolean = candidateTime < currentTime ||
    (candidateTime == currentTime && candidateSplits.count { it != null } > currentSplits.count { it != null })
