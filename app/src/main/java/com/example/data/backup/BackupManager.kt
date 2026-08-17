package com.example.data.backup

import android.content.Context
import android.database.Cursor
import com.example.data.backup.BackupValue.Int64
import com.example.data.backup.BackupValue.Null
import com.example.data.backup.BackupValue.Real
import com.example.data.backup.BackupValue.Text
import com.example.data.database.AppDatabase
import com.example.data.security.SecretStore
import java.nio.charset.StandardCharsets

/**
 * The whole app-owned workspace in one encrypted file: work schedules, plan boards with their
 * columns, items, blocks, dependencies, item schedules, saved views, baselines and non-secret
 * settings.
 *
 * What is deliberately not in the file: device events (the calendar is the source of truth and
 * re-syncs after a restore) and the mutation journal (an undo history written before a restore
 * describes rows that no longer exist). Restoring wipes both the journal and the current plan
 * tables and replaces them with the file's rows in one transaction — half a restore is worse
 * than none, so the write either lands whole or not at all.
 *
 * The file is encrypted with the Keystore-backed key ([SecretStore]) and the plaintext codec is
 * [RowBackupCodec]; a wrong key, a hand-edited file, or a file from another device all refuse
 * to decode rather than silently restoring partial data.
 */
class BackupManager(context: Context) {
  private val database = AppDatabase.getDatabase(context)
  private val secretStore = SecretStore(context)

  fun exportBackup(): ByteArray {
    val rows = LinkedHashMap<String, List<Map<String, BackupValue>>>()
    EXPORT_TABLES.forEach { table ->
      rows[table] =
        database.openHelper.readableDatabase
          .query("SELECT * FROM $table")
          .use { cursor -> readRows(cursor) }
    }
    val plaintext = RowBackupCodec.encode(rows)
    val encrypted = secretStore.encryptBackup(plaintext)
    return (ENCRYPTED_MAGIC + "\n" + encrypted).toByteArray(StandardCharsets.UTF_8)
  }

  /** Restores the file's rows, wiping what is there now. Returns the number of rows written. */
  fun restoreBackup(bytes: ByteArray): Int {
    val text = String(bytes, StandardCharsets.UTF_8)
    require(text.startsWith(ENCRYPTED_MAGIC)) { "Not a Daily Brief backup" }
    val encrypted = text.substringAfter('\n')
    val plaintext =
      secretStore.decryptBackup(encrypted) ?: throw IllegalArgumentException("Backup could not be decrypted")
    val rows = RowBackupCodec.decode(plaintext)
    rows.keys.forEach { table ->
      require(table in ALL_TABLES) { "Unknown table '$table' in backup" }
    }

    val db = database.openHelper.writableDatabase
    db.execSQL("PRAGMA foreign_keys=OFF")
    db.beginTransaction()
    try {
      // Children first, so the deletions never trip their own foreign keys.
      DELETE_TABLES.forEach { table -> db.execSQL("DELETE FROM $table") }
      var written = 0
      rows.forEach { (table, tableRows) ->
        if (tableRows.isEmpty()) return@forEach
        val columns = tableRows.first().keys.toList()
        val placeholders = columns.joinToString(",") { "?" }
        val statement =
          db.compileStatement(
            "INSERT INTO $table (${columns.joinToString(",") { "`$it`" }}) VALUES ($placeholders)"
          )
        tableRows.forEach { row ->
          columns.forEachIndexed { index, column ->
            bind(statement, index + 1, row[column])
          }
          statement.executeInsert()
          written++
        }
        statement.close()
      }
      db.setTransactionSuccessful()
      return written
    } finally {
      db.endTransaction()
      db.execSQL("PRAGMA foreign_keys=ON")
    }
  }

  private fun bind(
    statement: androidx.sqlite.db.SupportSQLiteStatement,
    index: Int,
    value: BackupValue?,
  ) {
    when (value) {
      is Text -> statement.bindString(index, value.value)
      is Int64 -> statement.bindLong(index, value.value)
      is Real -> statement.bindDouble(index, value.value)
      is Null, null -> statement.bindNull(index)
    }
  }

  private fun readRows(cursor: Cursor): List<Map<String, BackupValue>> {
    val names = cursor.columnNames
    return buildList {
      while (cursor.moveToNext()) {
        val row = LinkedHashMap<String, BackupValue>()
        names.forEachIndexed { index, name ->
          row[name] =
            when (cursor.getType(index)) {
              Cursor.FIELD_TYPE_NULL -> Null
              Cursor.FIELD_TYPE_INTEGER -> Int64(cursor.getLong(index))
              Cursor.FIELD_TYPE_FLOAT -> Real(cursor.getDouble(index))
              else -> Text(cursor.getString(index))
            }
        }
        add(row)
      }
    }
  }

  companion object {
    private const val ENCRYPTED_MAGIC = "dailybrief-backup-encrypted-v1"

    /** Parents before children, so the file reads naturally and ids resolve. */
    private val EXPORT_TABLES =
      listOf(
        "work_schedules",
        "work_schedule_windows",
        "plan_boards",
        "plan_columns",
        "plan_items",
        "plan_blocks",
        "plan_dependencies",
        "plan_item_schedules",
        "saved_views",
        "plan_baselines",
        "system_settings",
      )

    private val DELETE_TABLES =
      listOf(
        "system_settings",
        "plan_baselines",
        "saved_views",
        "plan_item_schedules",
        "plan_dependencies",
        "plan_blocks",
        "plan_items",
        "plan_columns",
        "plan_boards",
        "work_schedule_windows",
        "work_schedules",
        "plan_mutations",
      )

    private val ALL_TABLES = EXPORT_TABLES + "plan_mutations"
  }
}
