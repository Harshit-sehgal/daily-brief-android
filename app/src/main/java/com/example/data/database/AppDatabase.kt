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
import com.example.data.model.PlanBaseline
import com.example.data.model.PlanBlock
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import com.example.data.model.PlanItemSchedule
import com.example.data.model.PlanMutation
import com.example.data.model.SavedView
import com.example.data.model.SystemSetting
import com.example.data.model.WorkSchedule
import com.example.data.model.WorkScheduleWindow
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

  @Query("SELECT * FROM briefing_events WHERE id IN (:ids)")
  suspend fun getEventsByIds(ids: List<String>): List<BriefingEvent>

  @Query("SELECT * FROM briefing_events WHERE kanbanBoard = :board ORDER BY id")
  suspend fun getEventsForBoardSync(board: String): List<BriefingEvent>

  @Query(
    "SELECT * FROM briefing_events WHERE kanbanBoard = :board AND kanbanStatus = :status " +
      "ORDER BY id"
  )
  suspend fun getEventsForBoardColumnSync(board: String, status: String): List<BriefingEvent>

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

  /**
   * A deleted board can contain ad-hoc statuses that the fallback board does not expose.
   * Preserve recognized statuses and route only the rest, so no event becomes invisible.
   */
  @Query(
    "UPDATE briefing_events SET kanbanBoard = :newBoard, " +
      "kanbanStatus = CASE WHEN kanbanStatus IN (:validStatuses) " +
      "THEN kanbanStatus ELSE :fallbackStatus END WHERE kanbanBoard = :oldBoard"
  )
  suspend fun moveEventsToBoardWithColumnFallback(
    oldBoard: String,
    newBoard: String,
    validStatuses: List<String>,
    fallbackStatus: String,
  )

  @Query(
    "UPDATE briefing_events SET kanbanStatus = :newStatus WHERE kanbanBoard = :board AND kanbanStatus = :oldStatus"
  )
  suspend fun moveEventsToColumn(board: String, oldStatus: String, newStatus: String)
}

@Dao
interface BriefingDao {
  @Query("SELECT * FROM daily_briefings WHERE dateString = :dateString")
  suspend fun getBriefingForDate(dateString: String): DailyBriefing?

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBriefing(briefing: DailyBriefing)

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
  entities = [
    BriefingEvent::class,
    DailyBriefing::class,
    SystemSetting::class,
    PlanBoard::class,
    PlanColumn::class,
    PlanItem::class,
    PlanBlock::class,
    PlanDependency::class,
    SavedView::class,
    WorkSchedule::class,
    WorkScheduleWindow::class,
    PlanItemSchedule::class,
    PlanMutation::class,
    PlanBaseline::class,
  ],
  version = 10,
  exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun eventDao(): EventDao

  abstract fun briefingDao(): BriefingDao

  abstract fun settingDao(): SettingDao

  abstract fun planDao(): PlanDao

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
              .addMigrations(
                MIGRATION_3_4,
                MIGRATION_4_5,
                PlanMigrations.MIGRATION_5_6,
                PlanMigrations.MIGRATION_6_7,
                PlanMigrations.MIGRATION_7_8,
                PlanMigrations.MIGRATION_8_9,
                PlanMigrations.MIGRATION_9_10,
              )
              // Room cannot express partial indexes, so the "one non-archived default
              // schedule" constraint rides the onCreate callback for fresh installs;
              // upgrades get it from MIGRATION_9_10. Idempotent either way.
              .addCallback(
                object : RoomDatabase.Callback() {
                  override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    db.execSQL(
                      "CREATE UNIQUE INDEX IF NOT EXISTS index_work_schedules_one_non_archived_default " +
                        "ON work_schedules (isDefault) WHERE isDefault = 1 AND archivedAt IS NULL"
                    )
                  }
                },
              )
              // Versions 1 and 2 only ever existed on development builds, and
              // there is no migration for them — without this, opening one of
              // those installs crashes instead of starting fresh.
              .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2)
              .build()
              .also { INSTANCE = it }
        }
    }
  }
}
