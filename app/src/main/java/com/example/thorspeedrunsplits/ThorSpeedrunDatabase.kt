package com.example.thorspeedrunsplits

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Entity(tableName = "personal_best_runs")
data class PersonalBestRunEntity(
    @PrimaryKey val presetName: String,
    val splitTimesMillis: List<Long>,
    val finalTimeMillis: Long,
    val updatedAtMillis: Long
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
    entities = [PersonalBestRunEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(LongListConverters::class)
abstract class ThorSpeedrunDatabase : RoomDatabase() {
    abstract fun personalBestRunDao(): PersonalBestRunDao

    companion object {
        @Volatile
        private var instance: ThorSpeedrunDatabase? = null

        fun getInstance(context: Context): ThorSpeedrunDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ThorSpeedrunDatabase::class.java,
                    "thor_speedrun_splits.db"
                ).build().also { instance = it }
            }
        }
    }
}
