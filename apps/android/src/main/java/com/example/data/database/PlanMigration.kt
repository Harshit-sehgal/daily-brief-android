package com.example.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.prefs.SettingKeys
import java.util.Calendar
import java.util.TimeZone

object PlanMigrations {
  /** Aliased so migration SQL keeps the same names; the identity itself lives in planning-core. */
  val DEFAULT_WORK_SCHEDULE_ID: String = WorkScheduleDefaults.ID
  val DEFAULT_WORK_SCHEDULE_NAME: String = WorkScheduleDefaults.NAME

  /**
   * Adds the app-owned planning overlay without rewriting any calendar event,
   * briefing, or existing setting. Only a new derived active-board setting is added.
   */
  val MIGRATION_5_6: Migration =
    object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        createTables(db)
        importLegacyCatalog(db)
      }
    }

  /**
   * Adds normalized working calendars and a durable planning-mutation journal. Existing planning,
   * event, briefing, and setting rows are not rewritten.
   */
  val MIGRATION_6_7: Migration =
    object : Migration(6, 7) {
      override fun migrate(db: SupportSQLiteDatabase) {
        createWorkScheduleAndMutationTables(db)
        seedDefaultWorkSchedule(db)
      }
    }

  /**
   * Adds baselines. Purely additive: no existing table is touched, so every saved integration, key,
   * board, task and journal entry survives untouched.
   */
  val MIGRATION_7_8: Migration =
    object : Migration(7, 8) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS plan_baselines (" +
            "id TEXT NOT NULL, boardId TEXT NOT NULL, name TEXT NOT NULL, nameKey TEXT NOT NULL, " +
            "capturedAt INTEGER NOT NULL, itemsJson TEXT NOT NULL, blocksJson TEXT NOT NULL, " +
            "PRIMARY KEY(id))"
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_plan_baselines_boardId_capturedAt " +
            "ON plan_baselines (boardId, capturedAt)"
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS index_plan_baselines_boardId_nameKey " +
            "ON plan_baselines (boardId, nameKey)"
        )
      }
    }

  /**
   * Gives the catalog the two constraints that previously lived only in application code
   * (docs/saas/03-domain-and-architecture.md §4): one saved view per name per board+surface,
   * and at most one non-archived default working schedule. Indexes are additive, so the
   * migration is a pair of CREATE INDEX statements — no rebuild, no copy, no data risk.
   *
   * Room cannot express partial indexes, so the work_schedules one also lands in an onCreate
   * callback for fresh installs (see AppDatabase); the exported schema models the
   * saved_views index natively.
   */
  val MIGRATION_9_10: Migration =
    object : Migration(9, 10) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_views_boardId_surface_nameKey` " +
            "ON `saved_views` (`boardId`, `surface`, `nameKey`)"
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS index_work_schedules_one_non_archived_default " +
            "ON work_schedules (isDefault) WHERE isDefault = 1 AND archivedAt IS NULL"
        )
      }
    }

  /**
   * Gives baselines the foreign key they should have had.
   *
   * SQLite cannot add one to a live table, so the table is rebuilt and its rows copied. Any baseline
   * whose board is already gone is dropped on the way through — it describes a plan that cannot be
   * opened, and keeping it would make the new constraint unsatisfiable. Every baseline of a board
   * that still exists survives with its snapshot intact.
   */
  val MIGRATION_8_9: Migration =
    object : Migration(8, 9) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS plan_baselines_new (" +
            "id TEXT NOT NULL, boardId TEXT NOT NULL, name TEXT NOT NULL, nameKey TEXT NOT NULL, " +
            "capturedAt INTEGER NOT NULL, itemsJson TEXT NOT NULL, blocksJson TEXT NOT NULL, " +
            "PRIMARY KEY(id), " +
            "FOREIGN KEY(boardId) REFERENCES plan_boards(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
          "INSERT OR IGNORE INTO plan_baselines_new " +
            "(id, boardId, name, nameKey, capturedAt, itemsJson, blocksJson) " +
            "SELECT b.id, b.boardId, b.name, b.nameKey, b.capturedAt, b.itemsJson, b.blocksJson " +
            "FROM plan_baselines AS b " +
            "WHERE EXISTS (SELECT 1 FROM plan_boards WHERE plan_boards.id = b.boardId)"
        )
        db.execSQL("DROP TABLE plan_baselines")
        db.execSQL("ALTER TABLE plan_baselines_new RENAME TO plan_baselines")
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_plan_baselines_boardId_capturedAt " +
            "ON plan_baselines (boardId, capturedAt)"
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS index_plan_baselines_boardId_nameKey " +
            "ON plan_baselines (boardId, nameKey)"
        )
      }
    }

  private fun createWorkScheduleAndMutationTables(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS work_schedules (" +
        "id TEXT NOT NULL, name TEXT NOT NULL, nameKey TEXT NOT NULL, " +
        "timeZoneId TEXT NOT NULL, isDefault INTEGER NOT NULL, " +
        "minimumChunkMinutes INTEGER NOT NULL, maximumChunkMinutes INTEGER NOT NULL, " +
        "bufferMinutes INTEGER NOT NULL, rank INTEGER NOT NULL, archivedAt INTEGER, " +
        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
    )
    db.execSQL(
      "CREATE UNIQUE INDEX IF NOT EXISTS index_work_schedules_nameKey " +
        "ON work_schedules (nameKey)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS index_work_schedules_isDefault_rank " +
        "ON work_schedules (isDefault, rank)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS index_work_schedules_archivedAt " +
        "ON work_schedules (archivedAt)"
    )

    db.execSQL(
      "CREATE TABLE IF NOT EXISTS work_schedule_windows (" +
        "id TEXT NOT NULL, scheduleId TEXT NOT NULL, kind TEXT NOT NULL, " +
        "dayOfWeek INTEGER, localDate TEXT, startMinute INTEGER NOT NULL, " +
        "endMinute INTEGER NOT NULL, isClosed INTEGER NOT NULL, rank INTEGER NOT NULL, " +
        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), " +
        "FOREIGN KEY(scheduleId) REFERENCES work_schedules(id) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS index_work_schedule_windows_scheduleId_kind_dayOfWeek_rank " +
        "ON work_schedule_windows (scheduleId, kind, dayOfWeek, rank)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS index_work_schedule_windows_scheduleId_kind_localDate_rank " +
        "ON work_schedule_windows (scheduleId, kind, localDate, rank)"
    )

    db.execSQL(
      "CREATE TABLE IF NOT EXISTS plan_item_schedules (" +
        "planItemId TEXT NOT NULL, workScheduleId TEXT NOT NULL, " +
        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(planItemId), " +
        "FOREIGN KEY(planItemId) REFERENCES plan_items(id) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(workScheduleId) REFERENCES work_schedules(id) " +
        "ON UPDATE NO ACTION ON DELETE NO ACTION)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS index_plan_item_schedules_workScheduleId " +
        "ON plan_item_schedules (workScheduleId)"
    )

    db.execSQL(
      "CREATE TABLE IF NOT EXISTS plan_mutations (" +
        "id TEXT NOT NULL, boardId TEXT, mutationType TEXT NOT NULL, " +
        "targetType TEXT NOT NULL, targetIdsJson TEXT NOT NULL, summary TEXT NOT NULL, " +
        "beforeJson TEXT, afterJson TEXT, status TEXT NOT NULL, origin TEXT NOT NULL, " +
        "schemaVersion INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
        "updatedAt INTEGER NOT NULL, undoneAt INTEGER, expiresAt INTEGER, PRIMARY KEY(id), " +
        "FOREIGN KEY(boardId) REFERENCES plan_boards(id) " +
        "ON UPDATE NO ACTION ON DELETE SET NULL)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS index_plan_mutations_boardId_createdAt " +
        "ON plan_mutations (boardId, createdAt)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS index_plan_mutations_status_createdAt " +
        "ON plan_mutations (status, createdAt)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS index_plan_mutations_expiresAt " +
        "ON plan_mutations (expiresAt)"
    )
  }

  private fun seedDefaultWorkSchedule(db: SupportSQLiteDatabase) {
    val now = System.currentTimeMillis()
    db.execSQL(
      "INSERT OR IGNORE INTO work_schedules " +
        "(id,name,nameKey,timeZoneId,isDefault,minimumChunkMinutes,maximumChunkMinutes," +
        "bufferMinutes,rank,archivedAt,createdAt,updatedAt) " +
        "VALUES (?,?,?,?,?,?,?,?,?,NULL,?,?)",
      arrayOf<Any?>(
        DEFAULT_WORK_SCHEDULE_ID,
        DEFAULT_WORK_SCHEDULE_NAME,
        LegacyPlanCatalogBuilder.nameKey(DEFAULT_WORK_SCHEDULE_NAME),
        TimeZone.getDefault().id,
        1,
        30,
        120,
        0,
        1_000_000L,
        now,
        now,
      ),
    )
    (Calendar.MONDAY..Calendar.FRIDAY).forEachIndexed { index, dayOfWeek ->
      db.execSQL(
        "INSERT OR IGNORE INTO work_schedule_windows " +
          "(id,scheduleId,kind,dayOfWeek,localDate,startMinute,endMinute,isClosed,rank," +
          "createdAt,updatedAt) VALUES (?,?,?,?,NULL,?,?,?,?,?,?)",
        arrayOf<Any?>(
          "$DEFAULT_WORK_SCHEDULE_ID-weekday-$dayOfWeek",
          DEFAULT_WORK_SCHEDULE_ID,
          "weekly",
          dayOfWeek,
          9 * 60,
          17 * 60,
          0,
          (index + 1L) * 1_000_000L,
          now,
          now,
        ),
      )
    }
  }

  private fun createTables(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `plan_boards` (" +
        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, " +
        "`rank` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `archivedAt` INTEGER, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    db.execSQL(
      "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_boards_nameKey` " +
        "ON `plan_boards` (`nameKey`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_boards_rank` ON `plan_boards` (`rank`)"
    )

    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `plan_columns` (" +
        "`id` TEXT NOT NULL, `boardId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
        "`nameKey` TEXT NOT NULL, `rank` INTEGER NOT NULL, `archivedAt` INTEGER, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`boardId`) REFERENCES `plan_boards`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    db.execSQL(
      "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_columns_boardId_nameKey` " +
        "ON `plan_columns` (`boardId`, `nameKey`)"
    )
    db.execSQL(
      "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_columns_boardId_id` " +
        "ON `plan_columns` (`boardId`, `id`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_columns_boardId_rank` " +
        "ON `plan_columns` (`boardId`, `rank`)"
    )

    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `plan_items` (" +
        "`id` TEXT NOT NULL, `boardId` TEXT NOT NULL, `columnId` TEXT, `parentId` TEXT, " +
        "`title` TEXT NOT NULL, `notes` TEXT, `rank` INTEGER NOT NULL, " +
        "`startConstraint` INTEGER, `dueAt` INTEGER, `effortMinutes` INTEGER, " +
        "`progress` INTEGER NOT NULL, `priority` TEXT NOT NULL, `owner` TEXT, " +
        "`schedulingMode` TEXT NOT NULL, `locked` INTEGER NOT NULL, " +
        "`isMilestone` INTEGER NOT NULL, `completedAt` INTEGER, `archivedAt` INTEGER, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`boardId`) REFERENCES `plan_boards`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE NO ACTION, " +
        "FOREIGN KEY(`columnId`) REFERENCES `plan_columns`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE SET NULL, " +
        "FOREIGN KEY(`boardId`, `columnId`) REFERENCES `plan_columns`(`boardId`, `id`) " +
        "ON UPDATE NO ACTION ON DELETE NO ACTION, " +
        "FOREIGN KEY(`parentId`) REFERENCES `plan_items`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE SET NULL, " +
        "FOREIGN KEY(`boardId`, `parentId`) REFERENCES `plan_items`(`boardId`, `id`) " +
        "ON UPDATE NO ACTION ON DELETE NO ACTION)"
    )
    db.execSQL(
      "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_items_boardId_id` " +
        "ON `plan_items` (`boardId`, `id`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_items_boardId_columnId_rank` " +
        "ON `plan_items` (`boardId`, `columnId`, `rank`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_items_boardId_parentId` " +
        "ON `plan_items` (`boardId`, `parentId`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_items_columnId` ON `plan_items` (`columnId`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_items_parentId` ON `plan_items` (`parentId`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_items_dueAt` ON `plan_items` (`dueAt`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_items_archivedAt` ON `plan_items` (`archivedAt`)"
    )

    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `plan_blocks` (" +
        "`id` TEXT NOT NULL, `planItemId` TEXT NOT NULL, `startAt` INTEGER NOT NULL, " +
        "`endAt` INTEGER NOT NULL, `position` INTEGER NOT NULL, `locked` INTEGER NOT NULL, " +
        "`linkedEventId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`id`), FOREIGN KEY(`planItemId`) REFERENCES `plan_items`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_blocks_planItemId_startAt` " +
        "ON `plan_blocks` (`planItemId`, `startAt`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_blocks_startAt_endAt` " +
        "ON `plan_blocks` (`startAt`, `endAt`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_blocks_linkedEventId` " +
        "ON `plan_blocks` (`linkedEventId`)"
    )

    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `plan_dependencies` (" +
        "`id` TEXT NOT NULL, `boardId` TEXT NOT NULL, `predecessorId` TEXT NOT NULL, " +
        "`successorId` TEXT NOT NULL, `type` TEXT NOT NULL, `lagMinutes` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`predecessorId`) REFERENCES `plan_items`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(`successorId`) REFERENCES `plan_items`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(`boardId`, `predecessorId`) " +
        "REFERENCES `plan_items`(`boardId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION, " +
        "FOREIGN KEY(`boardId`, `successorId`) " +
        "REFERENCES `plan_items`(`boardId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION)"
    )
    db.execSQL(
      "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_dependencies_predecessorId_successorId` " +
        "ON `plan_dependencies` (`predecessorId`, `successorId`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_dependencies_successorId` " +
        "ON `plan_dependencies` (`successorId`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_dependencies_boardId_predecessorId` " +
        "ON `plan_dependencies` (`boardId`, `predecessorId`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_plan_dependencies_boardId_successorId` " +
        "ON `plan_dependencies` (`boardId`, `successorId`)"
    )

    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `saved_views` (" +
        "`id` TEXT NOT NULL, `boardId` TEXT, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, " +
        "`surface` TEXT NOT NULL, `filtersJson` TEXT NOT NULL, `grouping` TEXT NOT NULL, " +
        "`sortJson` TEXT NOT NULL, `columnsJson` TEXT NOT NULL, `rangeDays` INTEGER, " +
        "`zoom` TEXT, `collapsedIdsJson` TEXT NOT NULL, `pinned` INTEGER NOT NULL, " +
        "`rank` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`id`), FOREIGN KEY(`boardId`) REFERENCES `plan_boards`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_saved_views_boardId_surface_rank` " +
        "ON `saved_views` (`boardId`, `surface`, `rank`)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_saved_views_surface_pinned` " +
        "ON `saved_views` (`surface`, `pinned`)"
    )
  }

  private fun importLegacyCatalog(db: SupportSQLiteDatabase) {
    val settings = mutableMapOf<String, String>()
    db.query("SELECT `key`, `value` FROM `system_settings`").use { cursor ->
      val key = cursor.getColumnIndexOrThrow("key")
      val value = cursor.getColumnIndexOrThrow("value")
      while (cursor.moveToNext()) settings[cursor.getString(key)] = cursor.getString(value)
    }
    val eventDestinations = mutableListOf<Pair<String, String>>()
    db.query("SELECT DISTINCT `kanbanBoard`, `kanbanStatus` FROM `briefing_events`").use { cursor ->
      val board = cursor.getColumnIndexOrThrow("kanbanBoard")
      val column = cursor.getColumnIndexOrThrow("kanbanStatus")
      while (cursor.moveToNext()) {
        eventDestinations += cursor.getString(board) to cursor.getString(column)
      }
    }
    val catalog =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = settings[SettingKeys.BOARDS],
        activeBoardName = settings[SettingKeys.ACTIVE_BOARD],
        encodedColumns = { board -> settings[SettingKeys.columnsForBoard(board)] },
        eventDestinations = eventDestinations,
      )
    val now = System.currentTimeMillis()
    catalog.boards.forEach { board ->
      db.execSQL(
        "INSERT OR IGNORE INTO `plan_boards` " +
          "(`id`,`name`,`nameKey`,`rank`,`isDefault`,`archivedAt`,`createdAt`,`updatedAt`) " +
          "VALUES (?,?,?,?,?,NULL,?,?)",
        arrayOf<Any?>(
          board.id,
          board.name,
          board.nameKey,
          board.rank,
          if (board.isDefault) 1 else 0,
          now,
          now,
        ),
      )
      board.columns.forEach { column ->
        db.execSQL(
          "INSERT OR IGNORE INTO `plan_columns` " +
            "(`id`,`boardId`,`name`,`nameKey`,`rank`,`archivedAt`,`createdAt`,`updatedAt`) " +
            "VALUES (?,?,?,?,?,NULL,?,?)",
          arrayOf<Any?>(
            column.id,
            column.boardId,
            column.name,
            column.nameKey,
            column.rank,
            now,
            now,
          ),
        )
      }
    }
    db.execSQL(
      "INSERT OR REPLACE INTO `system_settings` (`key`,`value`) VALUES (?,?)",
      arrayOf(SettingKeys.ACTIVE_PLAN_BOARD_ID, catalog.activeBoardId),
    )
    db.execSQL(
      "INSERT OR REPLACE INTO `system_settings` (`key`,`value`) VALUES (?,?)",
      arrayOf(SettingKeys.PLAN_LEGACY_CATALOG_IMPORTED, "true"),
    )
  }
}
