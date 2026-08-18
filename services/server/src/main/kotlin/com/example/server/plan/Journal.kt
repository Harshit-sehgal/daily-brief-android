package com.example.server.plan

import com.example.contract.PlanProposalWire
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.newId
import com.example.server.db.Db.query
import com.example.server.db.Db.queryOne
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * The server journal: one `audit_entries` row per applied proposal, with the affected
 * `scheduled_blocks` rows captured as `before_json` / `after_json`. Undo is the WP-11
 * compare-and-set:
 *
 *  1. Claim the entry — `UPDATE ... SET undone_at = :now WHERE undone_at IS NULL AND
 *     expires_at > :now RETURNING *`. Zero rows: already undone or outside its window →
 *     refuse, never overwrite.
 *  2. Lock the affected blocks — `SELECT ... FOR UPDATE` in id order (the deadlock
 *     discipline: every multi-target transaction locks in the same order).
 *  3. Re-verify — the current rows must equal `before_json` exactly; a difference means the
 *     world moved since, and the undo is stale.
 *  4. Apply the inverse, record it as its own entry, commit.
 *
 * The device journal is Room-coupled (PlanMutationCodec); this is the server's own shape with
 * the same semantics — claim, lock, compare, refuse-or-apply.
 */
object Journal {
  private const val UNDO_WINDOW_SECONDS = 5 * 60
  private const val SCHEMA_VERSION = 1
  private val json = Json { encodeDefaults = true }

  @Serializable
  data class BlockRow(
    val id: String,
    val taskId: String,
    val startAt: Long,
    val endAt: Long,
    val position: Int,
    val locked: Boolean,
    val linkedEventId: String?,
  )

  fun applyProposal(
    workspaceId: String,
    projectId: String,
    proposals: List<PlanProposalWire>,
    nowMs: Long,
  ): Pair<List<BlockRow>, String> =
    Db.inTransaction { conn ->
      val blocks =
        proposals.map {
          BlockRow(
            id = newId(),
            taskId = it.itemId,
            startAt = it.startAt,
            endAt = it.endAt,
            position = 0,
            locked = false,
            linkedEventId = null,
          )
        }
      insertBlocks(conn, workspaceId, projectId, blocks)
      val entryId = newId()
      insertEntry(
        conn,
        workspaceId,
        projectId,
        EntryRecord(
          id = entryId,
          origin = "apply_proposal",
          summary = "Applied ${blocks.size} proposal${if (blocks.size == 1) "" else "s"}",
          before = emptyList(),
          after = blocks,
          undoneAt = null,
          expiresAt = nowMs + UNDO_WINDOW_SECONDS * 1000L,
        ),
      )
      blocks to entryId
    }

  /** Refuses (returns null) when the entry is already undone, expired, or stale. */
  fun undo(workspaceId: String, entryId: String, nowMs: Long): Int? =
    Db.inTransaction { conn ->
      val claim =
        conn.queryOne(
          "UPDATE audit_entries SET undone_at = ?, updated_at = ? " +
            "WHERE workspace_id = ? AND id = ? AND undone_at IS NULL AND expires_at > ? " +
            "RETURNING id, project_id, origin, before_json::text AS before_json, after_json::text AS after_json, expires_at",
          listOf(nowMs, nowMs, workspaceId, entryId, nowMs),
        ) { rs ->
          val projectId = rs.getString("project_id")
          val before = json.decodeFromString<List<BlockRow>>(rs.getString("before_json"))
          val after = json.decodeFromString<List<BlockRow>>(rs.getString("after_json"))
          ClaimedEntry(rs.getString("id"), projectId, before, after, rs.getLong("expires_at"))
        }
          ?: return@inTransaction null

      val targetIds = (claim.before + claim.after).map { it.id }.distinct().sorted()
      val locked =
        conn.query(
          "SELECT id, task_id, start_at, end_at, position, locked, linked_event_id " +
            "FROM scheduled_blocks WHERE workspace_id = ? AND project_id = ? AND id = ANY (?) ORDER BY id " +
            "FOR UPDATE",
          listOf(workspaceId, claim.projectId, targetIds),
        ) { rs -> rs.toBlockRow() }
      // CAS against the mutation's committed result: the current rows must equal what the
      // entry recorded it left behind (`after`). A block edited since apply differs → stale.
      // `before` is the inverse's content, not a state to find in the world — an insert
      // mutation's before is empty, and its rows now exist.
      if (locked != claim.after.sortedBy { it.id }) {
        // Stale: the world moved. Un-claim and refuse — never overwrite.
        conn.execute("UPDATE audit_entries SET undone_at = NULL, updated_at = ? WHERE id = ?", listOf(nowMs, entryId))
        return@inTransaction null
      }

      val removed = locked.size
      deleteBlocks(conn, workspaceId, claim.projectId, targetIds)
      insertBlocks(conn, workspaceId, claim.projectId, claim.before)
      insertEntry(
        conn,
        workspaceId,
        claim.projectId,
        EntryRecord(
          id = newId(),
          origin = "undo",
          summary = "Undid the applied proposal",
          before = claim.after,
          after = claim.before,
          undoneAt = null,
          expiresAt = nowMs + UNDO_WINDOW_SECONDS * 1000L,
        ),
      )
      removed
    }

  fun blocksFor(workspaceId: String, projectId: String): List<BlockRow> =
    Db.dataSource.connection.use { conn ->
      conn.query(
        "SELECT id, task_id, start_at, end_at, position, locked, linked_event_id " +
          "FROM scheduled_blocks WHERE workspace_id = ? AND project_id = ? ORDER BY start_at",
        listOf(workspaceId, projectId),
      ) { rs -> rs.toBlockRow() }
    }

  private data class EntryRecord(
    val id: String,
    val origin: String,
    val summary: String,
    val before: List<BlockRow>,
    val after: List<BlockRow>,
    val undoneAt: Long?,
    val expiresAt: Long,
  )

  private data class ClaimedEntry(
    val id: String,
    val projectId: String,
    val before: List<BlockRow>,
    val after: List<BlockRow>,
    val expiresAt: Long,
  )

  private fun insertBlocks(conn: Connection, workspaceId: String, projectId: String, blocks: List<BlockRow>) {
    blocks.forEach { b ->
      conn.execute(
        "INSERT INTO scheduled_blocks (id, project_id, task_id, workspace_id, start_at, end_at, position, locked, linked_event_id, created_at, updated_at) " +
          "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(
          b.id,
          projectId,
          b.taskId,
          workspaceId,
          b.startAt,
          b.endAt,
          b.position,
          b.locked,
          b.linkedEventId,
          System.currentTimeMillis(),
          System.currentTimeMillis(),
        ),
      )
    }
  }

  private fun deleteBlocks(conn: Connection, workspaceId: String, projectId: String, ids: List<String>) {
    conn.execute(
      "DELETE FROM scheduled_blocks WHERE workspace_id = ? AND project_id = ? AND id = ANY (?)",
      listOf(workspaceId, projectId, ids),
    )
  }

  private fun insertEntry(
    conn: Connection,
    workspaceId: String,
    projectId: String,
    entry: EntryRecord,
  ) {
    val now = System.currentTimeMillis()
    val targetIds = (entry.before + entry.after).map { it.id }.sorted().joinToString(",")
    conn.execute(
      "INSERT INTO audit_entries (id, project_id, workspace_id, mutation_type, target_type, target_ids_json, summary, before_json, after_json, status, origin, schema_version, created_at, updated_at, undone_at, expires_at) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)",
      listOf(
        entry.id,
        projectId,
        workspaceId,
        entry.origin,
        "plan_block",
        targetIds,
        entry.summary,
        json.encodeToString(entry.before),
        json.encodeToString(entry.after),
        "applied",
        entry.origin,
        SCHEMA_VERSION,
        now,
        now,
        entry.undoneAt,
        entry.expiresAt,
      ),
    )
  }

  private fun java.sql.ResultSet.toBlockRow() =
    BlockRow(
      id = getString("id"),
      taskId = getString("task_id"),
      startAt = getLong("start_at"),
      endAt = getLong("end_at"),
      position = getInt("position"),
      locked = getBoolean("locked"),
      linkedEventId = getString("linked_event_id"),
    )
}