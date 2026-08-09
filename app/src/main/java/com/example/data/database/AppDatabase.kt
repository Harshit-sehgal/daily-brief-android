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
   * Everything that overlaps the half-open [startInclusive, endExclusive)
   * window, not just everything that starts in it. Strict end comparisons keep
   * an event ending at midnight out of the following day.
   */
  @Query(
    "SELECT * FROM briefing_events WHERE startTime < :endExclusive AND endTime > :startInclusive ORDER BY startTime ASC"
  )
  fun getEventsInRange(startInclusive: Long, endExclusive: Long): Flow<List<BriefingEvent>>

  @Query(
    "SELECT * FROM briefing_events WHERE startTime < :endExclusive AND endTime > :startInclusive ORDER BY startTime ASC"
  )
  suspend fun getEventsInRangeSync(
    startInclusive: Long,
    endExclusive: Long,
  ): List<BriefingEvent>

  @Query("SELECT * FROM briefing_events WHERE id = :id") suspend fun getEventById(id: String): BriefingEvent?

  @Query("SELECT * FROM briefing_events WHERE kanbanBoard = :board ORDER BY startTime ASC")
  fun getEventsForBoard(board: String): Flow<List<BriefingEvent>>

  @Query("SELECT * FROM briefing_events WHERE source IN (:sources)")
  suspend fun getEventsBySources(sources: List<String>): List<BriefingEvent>

  @Query(
    "SELECT * FROM briefing_events WHERE source IN (:sources) AND startTime < :endExclusive AND endTime > :startInclusive"
  )
  suspend fun getEventsBySourcesInRange(
    sources: List<String>,
    startInclusive: Long,
    endExclusive: Long,
  ): List<BriefingEvent>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEvents(events: List<BriefingEvent>)

  @Query("DELETE FROM briefing_events WHERE source IN (:sources)")
  suspend fun clearEventsBySources(sources: List<String>)

  @Query(
    "DELETE FROM briefing_events WHERE source IN (:sources) AND startTime < :endExclusive AND endTime > :startInclusive"
  )
  suspend fun clearEventsBySourcesInRange(
    sources: List<String>,
    startInclusive: Long,
    endExclusive: Long,
  )

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
  version = 5,
  exportSchema = true,
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

    /** Adds real all-day state instead of continuing to infer it at runtime. */
    private val MIGRATION_4_5 =
      object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE briefing_events ADD COLUMN isAllDay INTEGER NOT NULL DEFAULT 0"
          )
          // Pre-v5 device all-day instances were stored as whole UTC-day spans.
          // Restrict the one-time inference to those provider rows so a 21-hour
          // manual event is never promoted to all-day.
          db.execSQL(
            "UPDATE briefing_events SET isAllDay = 1 " +
              "WHERE source IN ('Google Calendar', 'Samsung Calendar', 'Device Calendar') " +
              "AND endTime - startTime >= 86400000 " +
              "AND (endTime - startTime) % 86400000 = 0 " +
              "AND startTime % 86400000 = 0 " +
              "AND endTime % 86400000 = 0"
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
              .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
              .build()
              .also { INSTANCE = it }
        }
    }
  }
}
