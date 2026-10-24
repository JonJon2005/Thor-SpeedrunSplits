package com.example.thorspeedrunsplits

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SkippedSplitBackupTest {
    private fun bundle(times: List<Long?>, goldIndex: Int? = null): BackupBundle = BackupBundle(
        createdAtMillis = 42L, appVersion = "0.7", activePresetName = "Test",
        presets = listOf(BackupPreset(
            presetName = "Test", gameTitle = "Game", category = "Any%",
            segments = times.mapIndexed { i, _ -> BackupPresetSegment("Split $i", 0) },
            attemptedRuns = 1, totalTimeMillis = 30L,
            personalBest = BackupPersonalBest(times, times.last() ?: 30L, 40L),
            bestSegmentTimesMillis = times.map { null },
            runHistory = listOf(BackupHistoricalRun(
                "Game", "Any%", times.last() ?: 30L, 40L, true,
                times.mapIndexed { i, time -> BackupHistoricalSplit("Split $i", time, null, i == goldIndex) }
            ))
        ))
    )

    @Test fun skippedTimesSurviveRoundTripInHistoryAndPb() {
        val original = bundle(listOf(10L, null, 30L))
        assertEquals(original, parseBackupBundle(original.toJsonString()))
    }

    @Test fun consecutiveSkippedTimesSurviveRoundTrip() {
        val original = bundle(listOf(null, null, 30L))
        assertEquals(original, parseBackupBundle(original.toJsonString()))
    }

    @Test fun preSkipSchemaOneBackupsStillImport() {
        val original = bundle(listOf(10L, 20L, 30L), goldIndex = 1)
        val oldJson = JSONObject(original.toJsonString()).put("schemaVersion", 1).put("appVersion", "0.6.1")
        assertEquals(original.copy(appVersion = "0.6.1"), parseBackupBundle(oldJson.toString()))
    }

    @Test fun validatesCumulativeTimesAcrossSkippedRows() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBackupBundle(bundle(listOf(20L, null, 10L)).toJsonString())
        }
    }

    @Test fun rejectsSkippedFinishAndUnknownGoldDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBackupBundle(bundle(listOf(10L, 20L, null)).toJsonString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseBackupBundle(bundle(listOf(10L, null, 30L), goldIndex = 2).toJsonString())
        }
    }

    @Test fun missingTimeDoesNotSilentlyBecomeASkip() {
        val json = JSONObject(bundle(listOf(10L, 20L, 30L)).toJsonString())
        json.getJSONArray("presets").getJSONObject(0).getJSONArray("runHistory")
            .getJSONObject(0).getJSONArray("splits").getJSONObject(1).remove("splitTimeMillis")
        assertThrows(IllegalArgumentException::class.java) { parseBackupBundle(json.toString()) }
    }
}
