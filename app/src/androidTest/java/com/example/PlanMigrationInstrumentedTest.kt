package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.database.PlanMigrations
import com.example.data.prefs.SettingKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class PlanMigrationInstrumentedTest {
  private val databaseName = "plan-migration-${System.nanoTime()}"

  @After
  fun cleanUp() {
    InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
  }

  @Test
  fun migrationFrom5PreservesExistingRowsImportsStableCatalogAndSeedsWorkSchedule() {
    val customBoard = "Research, 深度"
    val encodedBoards = SettingKeys.encodeList(listOf(SettingKeys.DEFAULT_BOARD, customBoard))
    val encodedColumns = SettingKeys.encodeList(listOf("Inbox, triage", "Doing\ncarefully", "Done"))

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
      database.execSQL(
        "CREATE TABLE IF NOT EXISTS briefing_events (" +
          "id TEXT NOT NULL, title TEXT NOT NULL, startTime INTEGER NOT NULL, " +
          "endTime INTEGER NOT NULL, source TEXT NOT NULL, description TEXT, " +
          "isDeadline INTEGER NOT NULL, isUrgent INTEGER NOT NULL, isAllDay INTEGER NOT NULL, " +
          "location TEXT, kanbanStatus TEXT NOT NULL, kanbanBoard TEXT NOT NULL, " +
          "userEdited INTEGER NOT NULL, PRIMARY KEY(id))"
      )
      database.execSQL(
        "CREATE TABLE IF NOT EXISTS daily_briefings (" +
          "dateString TEXT NOT NULL, briefText TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
          "signature TEXT NOT NULL, PRIMARY KEY(dateString))"
      )
      database.execSQL(
        "CREATE TABLE IF NOT EXISTS system_settings (" +
          "`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))"
      )
      database.execSQL(
        "INSERT INTO briefing_events " +
          "(id,title,startTime,endTime,source,description,isDeadline,isUrgent,isAllDay," +
          "location,kanbanStatus,kanbanBoard,userEdited) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        arrayOf<Any?>(
          "provider_42",
          "Original title",
          1_777_000_000_000L,
          1_777_003_600_000L,
          "Device Calendar",
          "Keep every byte",
          1,
          0,
          0,
          "Studio 4",
          "Event-only status",
          "Event-only board",
          1,
        ),
      )
      database.execSQL(
        "INSERT INTO daily_briefings (dateString,briefText,createdAt,signature) VALUES (?,?,?,?)",
        arrayOf<Any?>("2026-08-11", "Existing brief", 1_776_000_000_000L, "signature-unchanged"),
      )
      database.execSQL(
        "INSERT INTO system_settings (`key`,`value`) VALUES (?,?)",
        arrayOf<Any?>(SettingKeys.BOARDS, encodedBoards),
      )
      database.execSQL(
        "INSERT INTO system_settings (`key`,`value`) VALUES (?,?)",
        arrayOf<Any?>(SettingKeys.ACTIVE_BOARD, customBoard),
      )
      database.execSQL(
        "INSERT INTO system_settings (`key`,`value`) VALUES (?,?)",
        arrayOf<Any?>(SettingKeys.columnsForBoard(customBoard), encodedColumns),
      )
      database.execSQL(
        "INSERT INTO system_settings (`key`,`value`) VALUES (?,?)",
        arrayOf<Any?>("sentinel_setting", "verbatim:value,with\nseparators"),
      )
      database.version = 5
    }

    // Opening through Room runs the migration and Room's generated schema
    // validator. This avoids a room-testing/serialization runtime conflict while
    // still failing on any table, index, or foreign-key mismatch.
    val room =
      Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(
          PlanMigrations.MIGRATION_5_6,
          PlanMigrations.MIGRATION_6_7,
          PlanMigrations.MIGRATION_7_8,
          PlanMigrations.MIGRATION_8_9,
        )
        .build()
    val migrated = room.openHelper.writableDatabase

    migrated.query("PRAGMA user_version").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(9, cursor.getInt(0))
    }
    migrated.query("SELECT * FROM briefing_events WHERE id = 'provider_42'").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("Original title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
      assertEquals(1_777_000_000_000L, cursor.getLong(cursor.getColumnIndexOrThrow("startTime")))
      assertEquals(1_777_003_600_000L, cursor.getLong(cursor.getColumnIndexOrThrow("endTime")))
      assertEquals("Device Calendar", cursor.getString(cursor.getColumnIndexOrThrow("source")))
      assertEquals("Keep every byte", cursor.getString(cursor.getColumnIndexOrThrow("description")))
      assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("isDeadline")))
      assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isUrgent")))
      assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isAllDay")))
      assertEquals("Studio 4", cursor.getString(cursor.getColumnIndexOrThrow("location")))
      assertEquals("Event-only status", cursor.getString(cursor.getColumnIndexOrThrow("kanbanStatus")))
      assertEquals("Event-only board", cursor.getString(cursor.getColumnIndexOrThrow("kanbanBoard")))
      assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("userEdited")))
    }
    migrated.query("SELECT * FROM daily_briefings WHERE dateString = '2026-08-11'").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("Existing brief", cursor.getString(cursor.getColumnIndexOrThrow("briefText")))
      assertEquals("signature-unchanged", cursor.getString(cursor.getColumnIndexOrThrow("signature")))
    }
    migrated.query("SELECT value FROM system_settings WHERE `key` = 'sentinel_setting'").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("verbatim:value,with\nseparators", cursor.getString(0))
    }
    migrated.query(
        "SELECT value FROM system_settings WHERE `key` = ?",
        arrayOf(SettingKeys.BOARDS),
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(encodedBoards, cursor.getString(0))
      }
    migrated.query(
        "SELECT value FROM system_settings WHERE `key` = ?",
        arrayOf(SettingKeys.columnsForBoard(customBoard)),
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(encodedColumns, cursor.getString(0))
      }

    val boardNames = mutableSetOf<String>()
    migrated.query("SELECT name FROM plan_boards").use { cursor ->
      while (cursor.moveToNext()) boardNames += cursor.getString(0)
    }
    assertEquals(setOf(SettingKeys.DEFAULT_BOARD, customBoard, "Event-only board"), boardNames)

    val customBoardId =
      migrated.query("SELECT id FROM plan_boards WHERE name = ?", arrayOf(customBoard)).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getString(0)
      }
    migrated.query(
        "SELECT value FROM system_settings WHERE `key` = ?",
        arrayOf(SettingKeys.ACTIVE_PLAN_BOARD_ID),
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(customBoardId, cursor.getString(0))
      }

    val customColumns = mutableSetOf<String>()
    migrated.query("SELECT name FROM plan_columns WHERE boardId = ?", arrayOf(customBoardId)).use { cursor ->
      while (cursor.moveToNext()) customColumns += cursor.getString(0)
    }
    assertEquals(setOf("Inbox, triage", "Doing\ncarefully", "Done"), customColumns)

    migrated.query("SELECT COUNT(*) FROM plan_items").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0, cursor.getInt(0))
    }
    migrated.query(
        "SELECT value FROM system_settings WHERE `key` = ?",
        arrayOf(SettingKeys.PLAN_LEGACY_CATALOG_IMPORTED),
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("true", cursor.getString(0))
      }
    assertDefaultWorkSchedule(migrated)

    // Re-running the table/seed body must not duplicate the stable schedule or its windows.
    PlanMigrations.MIGRATION_6_7.migrate(migrated)
    assertDefaultWorkSchedule(migrated)
    room.close()
  }

  @Test
  fun migrationFrom6PreservesRepresentativePlanningRows() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
      createV5Schema(database)
      database.version = 5
    }

    // The wrapper writes representative rows after the real 5 -> 6 migration and immediately
    // before the real 6 -> 7 migration. Room then validates the complete v7 schema on open.
    val migrateSeededV6 =
      object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
          insertRepresentativeV6Rows(db)
          PlanMigrations.MIGRATION_6_7.migrate(db)
        }
      }
    val room =
      Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(
          PlanMigrations.MIGRATION_5_6,
          migrateSeededV6,
          PlanMigrations.MIGRATION_7_8,
          PlanMigrations.MIGRATION_8_9,
        )
        .build()
    val migrated = room.openHelper.writableDatabase

    migrated.query("PRAGMA user_version").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(9, cursor.getInt(0))
    }
    migrated.query(
        "SELECT title, notes, columnId, parentId, dueAt, effortMinutes " +
          "FROM plan_items WHERE id = 'v6_successor'"
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("Preserved successor", cursor.getString(0))
        assertEquals("v6 payload", cursor.getString(1))
        assertEquals("v6_column", cursor.getString(2))
        assertEquals("v6_predecessor", cursor.getString(3))
        assertEquals(1_800_000L, cursor.getLong(4))
        assertEquals(90, cursor.getInt(5))
      }
    migrated.query(
        "SELECT startAt, endAt, linkedEventId FROM plan_blocks WHERE id = 'v6_block'"
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(600_000L, cursor.getLong(0))
        assertEquals(1_200_000L, cursor.getLong(1))
        assertEquals("fixed-event", cursor.getString(2))
      }
    migrated.query(
        "SELECT predecessorId, successorId, lagMinutes " +
          "FROM plan_dependencies WHERE id = 'v6_dependency'"
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("v6_predecessor", cursor.getString(0))
        assertEquals("v6_successor", cursor.getString(1))
        assertEquals(15, cursor.getInt(2))
      }
    migrated.query(
        "SELECT filtersJson, zoom, collapsedIdsJson FROM saved_views WHERE id = 'v6_view'"
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("{\"priority\":\"high\"}", cursor.getString(0))
        assertEquals("week", cursor.getString(1))
        assertEquals("[\"v6_predecessor\"]", cursor.getString(2))
      }
    listOf(
        "v6_board" to "plan_boards",
        "v6_column" to "plan_columns",
        "v6_predecessor" to "plan_items",
        "v6_block" to "plan_blocks",
        "v6_dependency" to "plan_dependencies",
        "v6_view" to "saved_views",
      )
      .forEach { (id, table) ->
        migrated.query("SELECT COUNT(*) FROM $table WHERE id = ?", arrayOf(id)).use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals("Expected $id to survive 6 -> 7", 1, cursor.getInt(0))
        }
      }
    assertDefaultWorkSchedule(migrated)
    room.close()
  }

  @Test
  fun migrationFrom7AddsBaselinesAndTheirForeignKeyWithoutDisturbingAnythingElse() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
      createV5Schema(database)
      database.version = 5
    }

    // Seed representative planning rows at v6, then let the real 7 -> 8 migration run on top of a
    // fully populated v7 database. Baselines are additive, so every one of those rows must survive.
    val migrateSeededV6 =
      object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
          insertRepresentativeV6Rows(db)
          PlanMigrations.MIGRATION_6_7.migrate(db)
        }
      }
    val room =
      Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(
          PlanMigrations.MIGRATION_5_6,
          migrateSeededV6,
          PlanMigrations.MIGRATION_7_8,
          PlanMigrations.MIGRATION_8_9,
        )
        .build()
    val migrated = room.openHelper.writableDatabase

    migrated.query("PRAGMA user_version").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(9, cursor.getInt(0))
    }
    listOf(
        "v6_board" to "plan_boards",
        "v6_column" to "plan_columns",
        "v6_predecessor" to "plan_items",
        "v6_block" to "plan_blocks",
        "v6_dependency" to "plan_dependencies",
        "v6_view" to "saved_views",
      )
      .forEach { (id, table) ->
        migrated.query("SELECT COUNT(*) FROM $table WHERE id = ?", arrayOf(id)).use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals("Expected $id to survive 7 -> 8", 1, cursor.getInt(0))
        }
      }
    assertDefaultWorkSchedule(migrated)

    // The new table exists, starts empty, and holds one baseline per name within a board.
    migrated.query("SELECT COUNT(*) FROM plan_baselines").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0, cursor.getInt(0))
    }
    migrated.execSQL(
      "INSERT INTO plan_baselines (id,boardId,name,nameKey,capturedAt,itemsJson,blocksJson) " +
        "VALUES ('b1','v6_board','Week one','week one',10,'{}','{}')"
    )
    // The board it names exists, so the foreign key added in 8 -> 9 is satisfied.
    val duplicateRejected =
      runCatching {
          migrated.execSQL(
            "INSERT INTO plan_baselines (id,boardId,name,nameKey,capturedAt,itemsJson,blocksJson) " +
              "VALUES ('b2','v6_board','Week One','week one',20,'{}','{}')"
          )
        }
        .isFailure
    assertTrue("Two baselines on one board may not share a name", duplicateRejected)

    // Re-running the body is harmless: an interrupted upgrade must be able to retry.
    PlanMigrations.MIGRATION_8_9.migrate(migrated)
    migrated.query("SELECT name FROM plan_baselines").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("Week one", cursor.getString(0))
      assertEquals(1, cursor.count)
    }
    room.close()
  }


  /**
   * The foreign key that 8 -> 9 adds cannot be applied in place, so the table is rebuilt. Rows have
   * to survive that, an already-orphaned baseline must not block the upgrade, and deleting a plan
   * afterwards has to take its baselines with it.
   */
  @Test
  fun migrationFrom8RebuildsBaselinesWithTheirForeignKeyAndKeepsRealRows() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
      createV5Schema(database)
      database.version = 5
    }

    // Seed a v8 database holding one baseline for a real board and one already orphaned.
    val seedAtV8 =
      object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "INSERT INTO plan_baselines (id,boardId,name,nameKey,capturedAt,itemsJson,blocksJson) " +
              "VALUES ('kept','v6_board','Week one','week one',10,'{\"a\":\"1\"}','{\"b\":\"2\"}')"
          )
          db.execSQL(
            "INSERT INTO plan_baselines (id,boardId,name,nameKey,capturedAt,itemsJson,blocksJson) " +
              "VALUES ('orphan','board_that_never_existed','Old','old',5,'{}','{}')"
          )
          PlanMigrations.MIGRATION_8_9.migrate(db)
        }
      }
    val migrateSeededV6 =
      object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
          insertRepresentativeV6Rows(db)
          PlanMigrations.MIGRATION_6_7.migrate(db)
        }
      }
    val room =
      Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(
          PlanMigrations.MIGRATION_5_6,
          migrateSeededV6,
          PlanMigrations.MIGRATION_7_8,
          seedAtV8,
        )
        .build()
    val migrated = room.openHelper.writableDatabase

    migrated.query("PRAGMA user_version").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(9, cursor.getInt(0))
    }
    // The real baseline came through the rebuild with its payload intact.
    migrated.query("SELECT boardId, itemsJson, blocksJson FROM plan_baselines WHERE id = 'kept'")
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("v6_board", cursor.getString(0))
        assertEquals("{\"a\":\"1\"}", cursor.getString(1))
        assertEquals("{\"b\":\"2\"}", cursor.getString(2))
      }
    // The orphan is dropped rather than blocking the upgrade.
    migrated.query("SELECT COUNT(*) FROM plan_baselines WHERE id = 'orphan'").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0, cursor.getInt(0))
    }

    // And the constraint is live: deleting the plan takes its baselines.
    migrated.execSQL("PRAGMA foreign_keys = ON")
    migrated.execSQL("DELETE FROM plan_items WHERE boardId = 'v6_board'")
    migrated.execSQL("DELETE FROM plan_boards WHERE id = 'v6_board'")
    migrated.query("SELECT COUNT(*) FROM plan_baselines").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(0, cursor.getInt(0))
    }
    room.close()
  }

  private fun createV5Schema(database: SQLiteDatabase) {
    database.execSQL(
      "CREATE TABLE IF NOT EXISTS briefing_events (" +
        "id TEXT NOT NULL, title TEXT NOT NULL, startTime INTEGER NOT NULL, " +
        "endTime INTEGER NOT NULL, source TEXT NOT NULL, description TEXT, " +
        "isDeadline INTEGER NOT NULL, isUrgent INTEGER NOT NULL, isAllDay INTEGER NOT NULL, " +
        "location TEXT, kanbanStatus TEXT NOT NULL, kanbanBoard TEXT NOT NULL, " +
        "userEdited INTEGER NOT NULL, PRIMARY KEY(id))"
    )
    database.execSQL(
      "CREATE TABLE IF NOT EXISTS daily_briefings (" +
        "dateString TEXT NOT NULL, briefText TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
        "signature TEXT NOT NULL, PRIMARY KEY(dateString))"
    )
    database.execSQL(
      "CREATE TABLE IF NOT EXISTS system_settings (" +
        "[key] TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY([key]))"
    )
  }

  private fun insertRepresentativeV6Rows(db: SupportSQLiteDatabase) {
    val createdAt = 1_000L
    db.execSQL(
      "INSERT INTO plan_boards " +
        "(id,name,nameKey,rank,isDefault,archivedAt,createdAt,updatedAt) " +
        "VALUES (?,?,?,?,?,NULL,?,?)",
      arrayOf<Any?>(
        "v6_board",
        "Preserved V6 board",
        "preserved v6 board",
        9_000_000L,
        0,
        createdAt,
        createdAt,
      ),
    )
    db.execSQL(
      "INSERT INTO plan_columns " +
        "(id,boardId,name,nameKey,rank,archivedAt,createdAt,updatedAt) " +
        "VALUES (?,?,?,?,?,NULL,?,?)",
      arrayOf<Any?>(
        "v6_column",
        "v6_board",
        "Preserved column",
        "preserved column",
        1_000_000L,
        createdAt,
        createdAt,
      ),
    )
    db.execSQL(
      "INSERT INTO plan_items " +
        "(id,boardId,columnId,parentId,title,notes,rank,startConstraint,dueAt,effortMinutes," +
        "progress,priority,owner,schedulingMode,locked,isMilestone,completedAt,archivedAt," +
        "createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      arrayOf<Any?>(
        "v6_predecessor",
        "v6_board",
        "v6_column",
        null,
        "Preserved predecessor",
        null,
        1_000_000L,
        null,
        null,
        30,
        25,
        "normal",
        "V6 owner",
        "auto",
        0,
        0,
        null,
        null,
        createdAt,
        createdAt,
      ),
    )
    db.execSQL(
      "INSERT INTO plan_items " +
        "(id,boardId,columnId,parentId,title,notes,rank,startConstraint,dueAt,effortMinutes," +
        "progress,priority,owner,schedulingMode,locked,isMilestone,completedAt,archivedAt," +
        "createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      arrayOf<Any?>(
        "v6_successor",
        "v6_board",
        "v6_column",
        "v6_predecessor",
        "Preserved successor",
        "v6 payload",
        2_000_000L,
        500_000L,
        1_800_000L,
        90,
        10,
        "high",
        "V6 owner",
        "manual",
        1,
        0,
        null,
        null,
        createdAt,
        createdAt + 1,
      ),
    )
    db.execSQL(
      "INSERT INTO plan_blocks " +
        "(id,planItemId,startAt,endAt,position,locked,linkedEventId,createdAt,updatedAt) " +
        "VALUES (?,?,?,?,?,?,?,?,?)",
      arrayOf<Any?>(
        "v6_block",
        "v6_successor",
        600_000L,
        1_200_000L,
        0,
        1,
        "fixed-event",
        createdAt,
        createdAt,
      ),
    )
    db.execSQL(
      "INSERT INTO plan_dependencies " +
        "(id,boardId,predecessorId,successorId,type,lagMinutes,createdAt,updatedAt) " +
        "VALUES (?,?,?,?,?,?,?,?)",
      arrayOf<Any?>(
        "v6_dependency",
        "v6_board",
        "v6_predecessor",
        "v6_successor",
        "finish_to_start",
        15,
        createdAt,
        createdAt,
      ),
    )
    db.execSQL(
      "INSERT INTO saved_views " +
        "(id,boardId,name,nameKey,surface,filtersJson,grouping,sortJson,columnsJson," +
        "rangeDays,zoom,collapsedIdsJson,pinned,rank,createdAt,updatedAt) " +
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      arrayOf<Any?>(
        "v6_view",
        "v6_board",
        "Preserved view",
        "preserved view",
        "gantt",
        "{\"priority\":\"high\"}",
        "column",
        "[\"dueAt\"]",
        "[\"title\",\"owner\"]",
        7,
        "week",
        "[\"v6_predecessor\"]",
        1,
        1_000_000L,
        createdAt,
        createdAt,
      ),
    )
  }

  private fun assertDefaultWorkSchedule(database: SupportSQLiteDatabase) {
    database.query(
        "SELECT name, timeZoneId, isDefault, minimumChunkMinutes, maximumChunkMinutes, " +
          "bufferMinutes FROM work_schedules WHERE id = ?",
        arrayOf(PlanMigrations.DEFAULT_WORK_SCHEDULE_ID),
      )
      .use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(PlanMigrations.DEFAULT_WORK_SCHEDULE_NAME, cursor.getString(0))
        assertEquals(TimeZone.getDefault().id, cursor.getString(1))
        assertEquals(1, cursor.getInt(2))
        assertEquals(30, cursor.getInt(3))
        assertEquals(120, cursor.getInt(4))
        assertEquals(0, cursor.getInt(5))
        assertTrue("The stable seed ID must remain unique", !cursor.moveToNext())
      }

    val days = mutableSetOf<Int>()
    database.query(
        "SELECT kind, dayOfWeek, localDate, startMinute, endMinute, isClosed " +
          "FROM work_schedule_windows WHERE scheduleId = ? ORDER BY dayOfWeek",
        arrayOf(PlanMigrations.DEFAULT_WORK_SCHEDULE_ID),
      )
      .use { cursor ->
        while (cursor.moveToNext()) {
          assertEquals("weekly", cursor.getString(0))
          days += cursor.getInt(1)
          assertTrue(cursor.isNull(2))
          assertEquals(9 * 60, cursor.getInt(3))
          assertEquals(17 * 60, cursor.getInt(4))
          assertEquals(0, cursor.getInt(5))
        }
      }
    assertEquals((Calendar.MONDAY..Calendar.FRIDAY).toSet(), days)
  }

  @Test
  fun migrationFrom9AddsTheTwoRealConstraintsAndRejectsTheirViolations() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
      createV5Schema(database)
      database.version = 5
    }
    val room =
      Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(
          PlanMigrations.MIGRATION_5_6,
          PlanMigrations.MIGRATION_6_7,
          PlanMigrations.MIGRATION_7_8,
          PlanMigrations.MIGRATION_8_9,
          PlanMigrations.MIGRATION_9_10,
        )
        .build()
    val migrated = room.openHelper.writableDatabase

    migrated.query("PRAGMA user_version").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(10, cursor.getInt(0))
    }

    // One saved view per (boardId, surface, nameKey): the first insert fits, a duplicate name
    // on the same board+surface is refused by the database itself.
    migrated.execSQL(
      "INSERT INTO saved_views (id,boardId,name,nameKey,surface,filtersJson,grouping,sortJson," +
        "columnsJson,collapsedIdsJson,pinned,rank,createdAt,updatedAt) " +
        "VALUES ('v1','b1','Week','week','plan','{}','none','[]','[]','[]',0,0,0,0)"
    )
    assertFailsWithConstraint {
      migrated.execSQL(
        "INSERT INTO saved_views (id,boardId,name,nameKey,surface,filtersJson,grouping,sortJson," +
          "columnsJson,collapsedIdsJson,pinned,rank,createdAt,updatedAt) " +
          "VALUES ('v2','b1','Week','week','plan','{}','none','[]','[]','[]',0,1,0,0)"
      )
    }

    // The same name on a different surface is a different view — the constraint is the pair.
    migrated.execSQL(
      "INSERT INTO saved_views (id,boardId,name,nameKey,surface,filtersJson,grouping,sortJson," +
        "columnsJson,collapsedIdsJson,pinned,rank,createdAt,updatedAt) " +
        "VALUES ('v3','b1','Week','week','today','{}','none','[]','[]','[]',0,2,0,0)"
    )

    // At most one non-archived default: a second default is refused; an archived default is a
    // different kind of row and stays legal.
    migrated.execSQL(
      "INSERT INTO work_schedules (id,name,nameKey,timeZoneId,isDefault,minimumChunkMinutes," +
        "maximumChunkMinutes,bufferMinutes,rank,createdAt,updatedAt) " +
        "VALUES ('s1','Default','default','UTC',1,30,120,0,0,0,0)"
    )
    assertFailsWithConstraint {
      migrated.execSQL(
        "INSERT INTO work_schedules (id,name,nameKey,timeZoneId,isDefault,minimumChunkMinutes," +
          "maximumChunkMinutes,bufferMinutes,rank,createdAt,updatedAt) " +
          "VALUES ('s2','Second','second','UTC',1,30,120,0,1,0,0)"
      )
    }
    migrated.execSQL(
      "INSERT INTO work_schedules (id,name,nameKey,timeZoneId,isDefault,minimumChunkMinutes," +
        "maximumChunkMinutes,bufferMinutes,rank,createdAt,updatedAt) " +
        "VALUES ('s3','Archived default','archived default','UTC',1,30,120,0,2,0,100)"
    )
    room.close()
  }

  private fun assertFailsWithConstraint(block: () -> Unit) {
    try {
      block()
      assertTrue("the database accepted a row its constraint should refuse", false)
    } catch (_: android.database.SQLException) {
      // the constraint did its job
    }
  }
}
