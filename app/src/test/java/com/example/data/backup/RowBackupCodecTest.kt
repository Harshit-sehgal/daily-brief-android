package com.example.data.backup

import com.example.data.backup.BackupValue.Int64
import com.example.data.backup.BackupValue.Null
import com.example.data.backup.BackupValue.Real
import com.example.data.backup.BackupValue.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RowBackupCodecTest {

  private fun sample() =
    mapOf(
      "plan_boards" to
        listOf(
          mapOf(
            "id" to Text("b1"),
            "name" to Text("Build"),
            "nameKey" to Text("build"),
            "rank" to Int64(1L),
            "isDefault" to Int64(0L),
            "archivedAt" to Null,
            "createdAt" to Int64(1000L),
            "updatedAt" to Int64(2000L),
          )
        ),
      "plan_items" to
        listOf(
          mapOf(
            "id" to Text("i1"),
            "boardId" to Text("b1"),
            "columnId" to Null,
            "parentId" to Null,
            "title" to Text("Ship the thing — tabs & newlines\ninside a title"),
            "notes" to Text("line one\nline two\\done"),
            "rank" to Int64(7L),
            "startConstraint" to Null,
            "dueAt" to Int64(1710000000000L),
            "effortMinutes" to Int64(45L),
            "progress" to Int64(50L),
            "priority" to Text("HIGH"),
            "owner" to Null,
            "schedulingMode" to Text("AUTO"),
            "locked" to Int64(0L),
            "archivedAt" to Null,
            "createdAt" to Int64(1000L),
            "updatedAt" to Int64(2000L),
          )
        ),
      "work_schedules" to listOf(mapOf("minGap" to Real(1.5), "tail" to Null)),
    )

  @Test
  fun `encode then decode is lossless, including newlines tabs and backslashes`() {
    val original = sample()

    val decoded = RowBackupCodec.decode(RowBackupCodec.encode(original))

    assertEquals(original, decoded)
  }

  @Test
  fun `table order and row order are preserved`() {
    val encoded = RowBackupCodec.encode(sample())
    val decoded = RowBackupCodec.decode(encoded)

    assertEquals(listOf("plan_boards", "plan_items", "work_schedules"), decoded.keys.toList())
    assertEquals(18, decoded["plan_items"]!!.single().size)
  }

  @Test
  fun `a document that is not a backup is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      RowBackupCodec.decode("hello world\nnot a backup")
    }
  }

  @Test
  fun `a truncated row is rejected rather than half-decoded`() {
    assertThrows(IllegalArgumentException::class.java) {
      RowBackupCodec.decode("dailybrief-backup\n1\nplan_boards\t1\nid\ts\nb1\n")
    }
  }

  @Test
  fun `empty tables round-trip`() {
    val decoded = RowBackupCodec.decode(RowBackupCodec.encode(mapOf("plan_boards" to emptyList())))

    assertEquals(emptyList<Map<String, BackupValue>>(), decoded["plan_boards"])
  }
}
