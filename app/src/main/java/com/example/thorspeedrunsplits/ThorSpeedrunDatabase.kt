package com.example.thorspeedrunsplits

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "personal_best_runs")
data class PersonalBestRunEntity(
    @PrimaryKey val presetName: String,
    val splitTimesMillis: List<Long>,
    val finalTimeMillis: Long,
    val updatedAtMillis: Long
)

@Entity(tableName = "best_segments")
data class BestSegmentsEntity(
    @PrimaryKey val presetName: String,
    val segmentTimesMillis: List<Long>,
    val updatedAtMillis: Long
)

@Entity(tableName = "split_presets")
data class SplitPresetEntity(
    @PrimaryKey val presetName: String,
    val gameTitle: String,
    val category: String,
    val attemptedRuns: Int,
    val totalTimeMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "split_preset_segments",
    primaryKeys = ["presetName", "position"],
    foreignKeys = [
        ForeignKey(
            entity = SplitPresetEntity::class,
            parentColumns = ["presetName"],
            childColumns = ["presetName"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("presetName")]
)
data class SplitPresetSegmentEntity(
    val presetName: String,
    val position: Int,
    val name: String,
    val markerColorArgb: Int
)

data class StoredSplitPreset(
    val preset: SplitPresetEntity,
    val segments: List<SplitPresetSegmentEntity>
)

@Entity(tableName = "app_preferences")
data class AppPreferenceEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface PersonalBestRunDao {
    @Query("SELECT * FROM personal_best_runs")
    suspend fun getAll(): List<PersonalBestRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: PersonalBestRunEntity)

    @Query("DELETE FROM personal_best_runs WHERE presetName = :presetName")
    suspend fun deleteByPresetName(presetName: String)
}

@Dao
interface BestSegmentsDao {
    @Query("SELECT * FROM best_segments")
    suspend fun getAll(): List<BestSegmentsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bestSegments: BestSegmentsEntity)

    @Query("DELETE FROM best_segments WHERE presetName = :presetName")
    suspend fun deleteByPresetName(presetName: String)
}

@Dao
interface SplitPresetDao {
    @Query("SELECT * FROM split_presets ORDER BY updatedAtMillis DESC")
    suspend fun getAllPresets(): List<SplitPresetEntity>

    @Query("SELECT * FROM split_preset_segments WHERE presetName = :presetName ORDER BY position ASC")
    suspend fun getSegmentsForPreset(presetName: String): List<SplitPresetSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(preset: SplitPresetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPresetIfAbsent(preset: SplitPresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SplitPresetSegmentEntity>)

    @Query("DELETE FROM split_preset_segments WHERE presetName = :presetName")
    suspend fun deleteSegmentsByPresetName(presetName: String)

    @Query("DELETE FROM split_presets WHERE presetName = :presetName")
    suspend fun deleteByPresetName(presetName: String)

    @Query(
        """
        UPDATE split_presets
        SET attemptedRuns = attemptedRuns + 1
        WHERE presetName = :presetName
        """
    )
    suspend fun incrementAttemptedRuns(presetName: String)

    @Query(
        """
        UPDATE split_presets
        SET totalTimeMillis = totalTimeMillis + :elapsedMillis
        WHERE presetName = :presetName
        """
    )
    suspend fun addTotalTime(presetName: String, elapsedMillis: Long)

    @Transaction
    suspend fun ensurePresetExists(
        preset: SplitPresetEntity,
        segments: List<SplitPresetSegmentEntity>
    ) {
        insertPresetIfAbsent(preset)
        insertSegments(segments)
    }

    @Transaction
    suspend fun getAllWithSegments(): List<StoredSplitPreset> {
        return getAllPresets().map { preset ->
            StoredSplitPreset(
                preset = preset,
                segments = getSegmentsForPreset(preset.presetName)
            )
        }
    }

    @Transaction
    suspend fun upsertWithSegments(
        preset: SplitPresetEntity,
        segments: List<SplitPresetSegmentEntity>
    ) {
        upsertPreset(preset)
        deleteSegmentsByPresetName(preset.presetName)
        insertSegments(segments)
    }
}

@Dao
interface AppPreferenceDao {
    @Query("SELECT value FROM app_preferences WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: AppPreferenceEntity)
}

class LongListConverters {
    @TypeConverter
    fun fromLongList(values: List<Long>): String {
        return values.joinToString(separator = ",")
    }

    @TypeConverter
    fun toLongList(value: String): List<Long> {
        if (value.isBlank()) {
            return emptyList()
        }
        return value.split(",").mapNotNull { it.toLongOrNull() }
    }
}

@Database(
    entities = [
        PersonalBestRunEntity::class,
        BestSegmentsEntity::class,
        SplitPresetEntity::class,
        SplitPresetSegmentEntity::class,
        AppPreferenceEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(LongListConverters::class)
abstract class ThorSpeedrunDatabase : RoomDatabase() {
    abstract fun personalBestRunDao(): PersonalBestRunDao
    abstract fun bestSegmentsDao(): BestSegmentsDao
    abstract fun splitPresetDao(): SplitPresetDao
    abstract fun appPreferenceDao(): AppPreferenceDao

    companion object {
        @Volatile
        private var instance: ThorSpeedrunDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `split_presets` (
                        `presetName` TEXT NOT NULL,
                        `gameTitle` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`presetName`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `split_preset_segments` (
                        `presetName` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `markerColorArgb` INTEGER NOT NULL,
                        PRIMARY KEY(`presetName`, `position`),
                        FOREIGN KEY(`presetName`) REFERENCES `split_presets`(`presetName`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_split_preset_segments_presetName` " +
                        "ON `split_preset_segments` (`presetName`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `split_presets` " +
                        "ADD COLUMN `attemptedRuns` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `split_presets` " +
                        "ADD COLUMN `totalTimeMillis` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_preferences` (
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `best_segments` (
                        `presetName` TEXT NOT NULL,
                        `segmentTimesMillis` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`presetName`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): ThorSpeedrunDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ThorSpeedrunDatabase::class.java,
                    "thor_speedrun_splits.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5
                    )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
