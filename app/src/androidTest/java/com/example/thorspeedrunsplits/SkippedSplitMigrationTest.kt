package com.example.thorspeedrunsplits

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SkippedSplitMigrationTest {
    @Test fun upgradesOldRunsAndPersistsSkippedSplitsWithoutDataLoss() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "skip-migration-test.db"
        context.deleteDatabase(name)
        // Frozen v6 schema: history split times were NOT NULL before skip support.
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val schema = listOf(
                """CREATE TABLE IF NOT EXISTS `personal_best_runs` (`presetName` TEXT NOT NULL, `splitTimesMillis` TEXT NOT NULL, `finalTimeMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`presetName`))""",
                """CREATE TABLE IF NOT EXISTS `best_segments` (`presetName` TEXT NOT NULL, `segmentTimesMillis` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`presetName`))""",
                """CREATE TABLE IF NOT EXISTS `completed_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `presetName` TEXT NOT NULL, `gameTitle` TEXT NOT NULL, `category` TEXT NOT NULL, `finalTimeMillis` INTEGER NOT NULL, `completedAtMillis` INTEGER NOT NULL, `wasPersonalBest` INTEGER NOT NULL)""",
                """CREATE INDEX IF NOT EXISTS `index_completed_runs_presetName` ON `completed_runs` (`presetName`)""",
                """CREATE TABLE IF NOT EXISTS `completed_run_splits` (`runId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `segmentName` TEXT NOT NULL, `splitTimeMillis` INTEGER NOT NULL, `comparisonSplitTimeMillis` INTEGER, `wasGold` INTEGER NOT NULL, PRIMARY KEY(`runId`, `position`), FOREIGN KEY(`runId`) REFERENCES `completed_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
                """CREATE INDEX IF NOT EXISTS `index_completed_run_splits_runId` ON `completed_run_splits` (`runId`)""",
                """CREATE TABLE IF NOT EXISTS `split_presets` (`presetName` TEXT NOT NULL, `gameTitle` TEXT NOT NULL, `category` TEXT NOT NULL, `attemptedRuns` INTEGER NOT NULL, `totalTimeMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`presetName`))""",
                """CREATE TABLE IF NOT EXISTS `split_preset_segments` (`presetName` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `markerColorArgb` INTEGER NOT NULL, PRIMARY KEY(`presetName`, `position`), FOREIGN KEY(`presetName`) REFERENCES `split_presets`(`presetName`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
                """CREATE INDEX IF NOT EXISTS `index_split_preset_segments_presetName` ON `split_preset_segments` (`presetName`)""",
                """CREATE TABLE IF NOT EXISTS `app_preferences` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))""",
            )
            schema.forEach { old.execSQL(it) }
            old.execSQL("INSERT INTO personal_best_runs VALUES ('Test', '10,30', 30, 1)")
            old.execSQL("INSERT INTO best_segments VALUES ('Test', '10,20', 1)")
            old.execSQL("INSERT INTO split_presets VALUES ('Test', 'Game', 'Any%', 2, 40, 1)")
            old.execSQL("INSERT INTO app_preferences VALUES ('theme_mode', 'oled')")
            old.execSQL("INSERT INTO completed_runs VALUES (1, 'Test', 'Game', 'Any%', 30, 1, 1)")
            old.execSQL("INSERT INTO completed_run_splits VALUES (1, 0, 'One', 10, 12, 1)")
            old.execSQL("INSERT INTO completed_run_splits VALUES (1, 1, 'Finish', 30, 35, 0)")
            old.version = 6
        }
        val database = Room.databaseBuilder(context, ThorSpeedrunDatabase::class.java, name)
            .addMigrations(ThorSpeedrunDatabase.MIGRATION_6_7).build()
        try {
            val oldRun = database.completedRunDao().getAllWithSplits().single()
            val oldSplits = oldRun.splits.sortedBy { it.position }
            assertEquals(listOf(10L, 30L), oldSplits.map { it.splitTimeMillis })
            assertEquals(listOf(12L, 35L), oldSplits.map { it.comparisonSplitTimeMillis })
            assertTrue(oldSplits.first().wasGold)
            assertEquals(listOf(10L, 30L), database.personalBestRunDao().getAll().single().splitTimesMillis)
            assertEquals(listOf(10L, 20L), database.bestSegmentsDao().getAll().single().segmentTimesMillis)
            assertEquals(2, database.splitPresetDao().getAllPresets().single().attemptedRuns)
            assertEquals("oled", database.appPreferenceDao().getValue("theme_mode"))
            database.completedRunDao().insertWithSplits(
                CompletedRunEntity(presetName = "Test", gameTitle = "Game", category = "Any%",
                    finalTimeMillis = 25, completedAtMillis = 2, wasPersonalBest = true),
                listOf(
                    CompletedRunSplitEntity(0, 0, "One", null, 10, false),
                    CompletedRunSplitEntity(0, 1, "Finish", 25, 30, false)
                )
            )
            val runs = database.completedRunDao().getAllWithSplits()
            assertEquals(2, runs.size)
            assertNull(runs.first().splits.first { it.position == 0 }.splitTimeMillis)
            database.personalBestRunDao().upsert(PersonalBestRunEntity("Test", listOf(-1L, 25L), 25L, 2L))
            assertEquals(listOf(-1L, 25L), database.personalBestRunDao().getAll().single().splitTimesMillis)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
