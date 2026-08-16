package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.security.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSafetyInstrumentedTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun databaseOpensAtCurrentSchemaWithAllDayColumn() {
    val database = AppDatabase.getDatabase(context).openHelper.writableDatabase

    database.query("PRAGMA user_version").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(9, cursor.getInt(0))
    }

    val columns = mutableSetOf<String>()
    database.query("PRAGMA table_info(`briefing_events`)").use { cursor ->
      val nameIndex = cursor.getColumnIndexOrThrow("name")
      while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
    }
    assertTrue("Room v5 must expose explicit all-day state", "isAllDay" in columns)

    val tables = mutableSetOf<String>()
    database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
      while (cursor.moveToNext()) tables += cursor.getString(0)
    }
    assertTrue("Room v6 must expose app-owned plan items", "plan_items" in tables)
    assertTrue("Room v6 must expose stable plan boards", "plan_boards" in tables)
    assertTrue("Room v7 must expose reusable work schedules", "work_schedules" in tables)
    assertTrue(
      "Room v7 must normalize weekly and date-specific windows",
      "work_schedule_windows" in tables,
    )
    assertTrue(
      "Room v7 must expose per-item schedule assignments",
      "plan_item_schedules" in tables,
    )
    assertTrue("Room v7 must expose a durable mutation journal", "plan_mutations" in tables)
  }

  @Test
  fun secretStoreRoundTripsWithoutPersistingPlaintext() {
    val key = "instrumentation_secret"
    val plaintext = "test-only-${System.nanoTime()}"
    val store = SecretStore(context)

    try {
      store.write(key, plaintext)
      assertEquals(plaintext, store.read(key))

      val encoded =
        context
          .getSharedPreferences("encrypted_integration_credentials", android.content.Context.MODE_PRIVATE)
          .getString(key, null)
      assertNotNull(encoded)
      assertFalse(encoded!!.contains(plaintext))
    } finally {
      store.delete(key)
    }
  }
}
