package com.example.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.prefs.SettingKeys
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class LegacyPlanBoard(
  val id: String,
  val name: String,
  val nameKey: String,
  val rank: Long,
  val isDefault: Boolean,
  val columns: List<LegacyPlanColumn>,
)

internal data class LegacyPlanColumn(
  val id: String,
  val boardId: String,
  val name: String,
  val nameKey: String,
  val rank: Long,
)

internal data class LegacyPlanCatalog(
  val boards: List<LegacyPlanBoard>,
  val activeBoardId: String,
)

/** Pure, idempotent conversion of name-based legacy board data into stable planning IDs. */
internal object LegacyPlanCatalogBuilder {
  fun build(
    encodedBoards: String?,
    activeBoardName: String?,
    encodedColumns: (String) -> String?,
    eventDestinations: List<Pair<String, String>>,
  ): LegacyPlanCatalog {
    val displayNames = mutableListOf<String>()
    displayNames +=
      SettingKeys.decodeList(encodedBoards)?.ifEmpty { null }
        ?: listOf(SettingKeys.DEFAULT_BOARD)
    displayNames += eventDestinations.map { it.first }

    val boardNames =
      displayNames
        .mapNotNull(::validDisplayName)
        .distinct()
        .ifEmpty { listOf(SettingKeys.DEFAULT_BOARD) }
    val boardKeys = collisionSafeNameKeys(boardNames, preferredCanonical = SettingKeys.DEFAULT_BOARD)

    val boards =
      boardNames.mapIndexed { boardIndex, boardName ->
        val boardKey = boardKeys.getValue(boardName)
        val boardId = stableId("legacy-board:$boardKey")
        val configured = SettingKeys.decodeList(encodedColumns(boardName))
        val eventColumns =
          eventDestinations
            .filter { it.first == boardName }
            .mapNotNull { validDisplayName(it.second) }
        val columnNames =
          ((configured ?: SettingKeys.DEFAULT_COLUMNS) + eventColumns)
            .mapNotNull(::validDisplayName)
            .distinct()
            .ifEmpty { SettingKeys.DEFAULT_COLUMNS }
        val columnKeys = collisionSafeNameKeys(columnNames)
        val columns =
          columnNames.mapIndexed { columnIndex, columnName ->
            val columnKey = columnKeys.getValue(columnName)
            LegacyPlanColumn(
              id = stableId("legacy-column:$boardId:$columnKey"),
              boardId = boardId,
              name = columnName,
              nameKey = columnKey,
              rank = rankFor(columnIndex),
            )
          }
        LegacyPlanBoard(
          id = boardId,
          name = boardName,
          nameKey = boardKey,
          rank = rankFor(boardIndex),
          isDefault = boardKey == nameKey(SettingKeys.DEFAULT_BOARD),
          columns = columns,
        )
      }

    val requestedName = validDisplayName(activeBoardName)
    val requestedKey = requestedName?.let(::nameKey)
    val active =
      boards.firstOrNull { it.name == requestedName }
        ?: boards.firstOrNull { nameKey(it.name) == requestedKey }
        ?: boards.firstOrNull { it.isDefault }
        ?: boards.first()
    return LegacyPlanCatalog(boards = boards, activeBoardId = active.id)
  }

  fun nameKey(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

  fun stableId(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

  /**
   * Room requires unique normalized keys, but v5 allowed visually similar Unicode names such as
   * `Work` and `Ｗｏｒｋ`. Keep every exact legacy identity and suffix only the colliding keys. The
   * UTF-16 encoding is reversible and collision-free, unlike a truncated hash.
   */
  private fun collisionSafeNameKeys(
    names: List<String>,
    preferredCanonical: String? = null,
  ): Map<String, String> {
    val byBase = names.groupBy(::nameKey)
    val canonicalByBase =
      byBase.mapValues { (_, collisions) ->
        preferredCanonical?.takeIf { it in collisions }
          ?: collisions.minOrNull()
          ?: error("A grouped legacy name set cannot be empty")
      }
    val keysByName =
      canonicalByBase.values.associateWithTo(mutableMapOf()) { canonical -> nameKey(canonical) }
    val reservedNaturalKeys = byBase.keys
    val generatedKeys = mutableSetOf<String>()

    names
      .asSequence()
      .filter { name -> canonicalByBase.getValue(nameKey(name)) != name }
      .sorted()
      .forEach { name ->
        val base = nameKey(name)
        var candidate = "$base#legacy-${utf16Hex(name)}"
        while (candidate in reservedNaturalKeys || !generatedKeys.add(candidate)) {
          candidate += "#"
        }
        keysByName[name] = candidate
      }

    return names.associateWith { name ->
      keysByName.getValue(name)
    }
  }

  private fun utf16Hex(value: String): String =
    buildString(value.length * 4) {
      value.forEach { character -> append(character.code.toString(16).padStart(4, '0')) }
    }

  /** Preserve the exact stored identity; reject only names that contain no visible content. */
  private fun validDisplayName(value: String?): String? = value?.takeIf(String::isNotBlank)

  private fun rankFor(index: Int): Long = (index + 1L) * RANK_GAP

  private const val RANK_GAP = 1_000_000L
}

object PlanMigrations {
  const val DEFAULT_WORK_SCHEDULE_ID = "default-work-schedule"
  const val DEFAULT_WORK_SCHEDULE_NAME = "Default working week"

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
