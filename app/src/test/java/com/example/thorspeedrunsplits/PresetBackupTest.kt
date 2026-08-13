package com.example.thorspeedrunsplits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PresetBackupTest {
    @Test
    fun backupBundleRoundTripsThroughJson() {
        val bundle = BackupBundle(
            createdAtMillis = 1_234L,
            appVersion = "0.5",
            activePresetName = "Any Percent",
            presets = listOf(
                BackupPreset(
                    presetName = "Any Percent",
                    gameTitle = "Thor Test",
                    category = "Any%",
                    segments = listOf(
                        BackupPresetSegment("Start", 0xFF112233.toInt()),
                        BackupPresetSegment("Finish", 0xFF445566.toInt())
                    ),
                    attemptedRuns = 7,
                    totalTimeMillis = 90_000L,
                    personalBest = BackupPersonalBest(
                        splitTimesMillis = listOf(10_000L, 25_000L),
                        finalTimeMillis = 25_000L,
                        completedAtMillis = 1_000L
                    ),
                    bestSegmentTimesMillis = listOf(9_000L, null),
                    runHistory = listOf(
                        BackupHistoricalRun(
                            gameTitle = "Thor Test",
                            category = "Any%",
                            finalTimeMillis = 25_000L,
                            completedAtMillis = 1_000L,
                            wasPersonalBest = true,
                            splits = listOf(
                                BackupHistoricalSplit(
                                    segmentName = "Start",
                                    splitTimeMillis = 10_000L,
                                    comparisonSplitTimeMillis = null,
                                    wasGold = true
                                ),
                                BackupHistoricalSplit(
                                    segmentName = "Finish",
                                    splitTimeMillis = 25_000L,
                                    comparisonSplitTimeMillis = null,
                                    wasGold = false
                                )
                            )
                        )
                    )
                )
            )
        )

        assertEquals(bundle, parseBackupBundle(bundle.toJsonString()))
    }

    @Test
    fun rejectsNonBackupJson() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            parseBackupBundle(
                """
                {
                  "format": "not-a-backup",
                  "schemaVersion": 1,
                  "createdAtMillis": 0,
                  "appVersion": "0.5",
                  "presets": []
                }
                """.trimIndent()
            )
        }

        assertEquals(
            "This JSON file is not a Thor Speedrun Splits backup.",
            exception.message
        )
    }

    @Test
    fun rejectsDecreasingHistoricalSplitTimes() {
        val bundle = BackupBundle(
            createdAtMillis = 1L,
            appVersion = "0.5",
            activePresetName = "Broken",
            presets = listOf(
                BackupPreset(
                    presetName = "Broken",
                    gameTitle = "Game",
                    category = "Any%",
                    segments = listOf(BackupPresetSegment("Finish", 0)),
                    attemptedRuns = 1,
                    totalTimeMillis = 1L,
                    personalBest = null,
                    bestSegmentTimesMillis = listOf(null),
                    runHistory = listOf(
                        BackupHistoricalRun(
                            gameTitle = "Game",
                            category = "Any%",
                            finalTimeMillis = 5L,
                            completedAtMillis = 1L,
                            wasPersonalBest = false,
                            splits = listOf(
                                BackupHistoricalSplit("One", 10L, null, false),
                                BackupHistoricalSplit("Two", 5L, null, false)
                            )
                        )
                    )
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            parseBackupBundle(bundle.toJsonString())
        }
    }
}
