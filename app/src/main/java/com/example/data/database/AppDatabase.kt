package com.example.data.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.DailyBriefing
import com.example.data.model.SystemSetting
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
  /**
   * Everything that *overlaps* the window, not just everything that starts in
   * it — otherwise an event running from last night into this morning is
   * missing from today.
   */
  @Query(
    "SELECT * FROM briefing_events WHERE startTime <= :end AND endTime >= :start ORDER BY startTime ASC"
  )
  fun getEventsInRange(start: Long, end: Long): Flow<List<BriefingEvent>>

  @Query(
    "SELECT * FROM briefing_events WHERE startTime <= :end AND endTime >= :start ORDER BY startTime ASC"
  )
  suspend fun getEventsInRangeSync(start: Long, end: Long): List<BriefingEvent>

  @Query("SELECT * FROM briefing_events WHERE id = :id") suspend fun getEventById(id: String): BriefingEvent?

  @Query("SELECT * FROM briefing_events WHERE kanbanBoard = :board ORDER BY startTime ASC")
  fun getEventsForBoard(board: String): Flow<List<BriefingEvent>>

  @Query("SELECT * FROM briefing_events WHERE source IN (:sources)")
  suspend fun getEventsBySources(sources: List<String>): List<BriefingEvent>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEvents(events: List<BriefingEvent>)

  @Query("DELETE FROM briefing_events WHERE source IN (:sources)")
  suspend fun clearEventsBySources(sources: List<String>)

  @Query("DELETE FROM briefing_events WHERE id = :id") suspend fun deleteEventById(id: String)

  /** Board renames/deletes have to reach every event, not only the visible day. */
  @Query("UPDATE briefing_events SET kanbanBoard = :newBoard WHERE kanbanBoard = :oldBoard")
  suspend fun moveEventsToBoard(oldBoard: String, newBoard: String)

  @Query(
    "UPDATE briefing_events SET kanbanStatus = :newStatus WHERE kanbanBoard = :board AND kanbanStatus = :oldStatus"
  )
  suspend fun moveEventsToColumn(board: String, oldStatus: String, newStatus: String)

  @Query("DELETE FROM briefing_events") suspend fun clearAllEvents()
}

@Dao
interface BriefingDao {
  @Query("SELECT * FROM daily_briefings WHERE dateString = :dateString")
  suspend fun getBriefingForDate(dateString: String): DailyBriefing?

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBriefing(briefing: DailyBriefing)

  @Query("DELETE FROM daily_briefings WHERE dateString = :dateString")
  suspend fun deleteBriefingForDate(dateString: String)

  @Query("DELETE FROM daily_briefings WHERE createdAt < :cutoff")
  suspend fun deleteBriefingsOlderThan(cutoff: Long)
}

@Dao
interface SettingDao {
  @Query("SELECT * FROM system_settings WHERE `key` = :key") fun getSetting(key: String): Flow<SystemSetting?>

  @Query("SELECT * FROM system_settings WHERE `key` = :key")
  suspend fun getSettingSync(key: String): SystemSetting?

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSetting(setting: SystemSetting)

  @Query("DELETE FROM system_settings WHERE `key` = :key") suspend fun deleteSetting(key: String)
}

@Database(
  entities = [BriefingEvent::class, DailyBriefing::class, SystemSetting::class],
  version = 4,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun eventDao(): EventDao

  abstract fun briefingDao(): BriefingDao

  abstract fun settingDao(): SettingDao

  companion object {
    /**
     * Adds the briefing cache signature and the hand-edited marker on events.
     * A real migration rather than a wipe, so saved integrations, API keys and
     * boards survive the upgrade.
     */
    private val MIGRATION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE daily_briefings ADD COLUMN signature TEXT NOT NULL DEFAULT ''")
          db.execSQL(
            "ALTER TABLE briefing_events ADD COLUMN userEdited INTEGER NOT NULL DEFAULT 0"
          )
        }
      }

    @Volatile private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
      return INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "daily_brief_database",
              )
              .addMigrations(MIGRATION_3_4)
              .fallbackToDestructiveMigration(dropAllTables = true)
              .build()
              .also { INSTANCE = it }
        }
    }
  }
}
