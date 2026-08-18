package com.example.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.PlanBaseline
import com.example.data.model.PlanBlock
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import com.example.data.model.PlanItemSchedule
import com.example.data.model.PlanMutation
import com.example.data.model.SavedView
import com.example.data.model.WorkSchedule
import com.example.data.model.WorkScheduleWindow
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
  @Query("SELECT * FROM plan_boards WHERE archivedAt IS NULL ORDER BY rank, name")
  fun observeBoards(): Flow<List<PlanBoard>>

  @Query("SELECT * FROM plan_boards WHERE archivedAt IS NULL ORDER BY rank, name")
  suspend fun getBoards(): List<PlanBoard>

  @Query("SELECT * FROM plan_boards ORDER BY rank, name")
  suspend fun getAllBoards(): List<PlanBoard>

  @Query("SELECT * FROM plan_boards WHERE id = :id") suspend fun getBoard(id: String): PlanBoard?

  /** Legacy board names are identities during the compatibility window; use SQLite's exact match. */
  @Query("SELECT * FROM plan_boards WHERE name = :name LIMIT 1")
  suspend fun getBoardByName(name: String): PlanBoard?

  @Query("SELECT * FROM plan_boards WHERE nameKey = :nameKey LIMIT 1")
  suspend fun getBoardByNameKey(nameKey: String): PlanBoard?

  @Query(
    "SELECT * FROM plan_columns WHERE boardId = :boardId AND archivedAt IS NULL " +
      "ORDER BY rank, name"
  )
  fun observeColumns(boardId: String): Flow<List<PlanColumn>>

  @Query(
    "SELECT * FROM plan_columns WHERE boardId = :boardId AND archivedAt IS NULL " +
      "ORDER BY rank, name"
  )
  suspend fun getColumns(boardId: String): List<PlanColumn>

  @Query("SELECT * FROM plan_columns WHERE id = :id") suspend fun getColumn(id: String): PlanColumn?

  @Query("SELECT * FROM plan_columns WHERE boardId = :boardId AND name = :name LIMIT 1")
  suspend fun getColumnByName(boardId: String, name: String): PlanColumn?

  @Query(
    "SELECT * FROM plan_columns WHERE boardId = :boardId AND nameKey = :nameKey LIMIT 1"
  )
  suspend fun getColumnByNameKey(boardId: String, nameKey: String): PlanColumn?

  @Query("SELECT * FROM plan_columns WHERE boardId = :boardId ORDER BY rank, name")
  suspend fun getAllColumns(boardId: String): List<PlanColumn>

  @Query(
    "SELECT * FROM plan_items WHERE boardId = :boardId AND archivedAt IS NULL " +
      "ORDER BY CASE WHEN columnId IS NULL THEN 0 ELSE 1 END, rank, createdAt"
  )
  fun observeItems(boardId: String): Flow<List<PlanItem>>

  @Transaction
  @Query(
    "SELECT * FROM plan_items WHERE boardId = :boardId AND archivedAt IS NULL " +
      "ORDER BY CASE WHEN columnId IS NULL THEN 0 ELSE 1 END, rank, createdAt"
  )
  fun observeItemsWithBlocks(boardId: String): Flow<List<PlanItemWithBlocks>>

  @Query("SELECT * FROM plan_items WHERE id = :id") suspend fun getItem(id: String): PlanItem?

  @Query("SELECT * FROM plan_items WHERE parentId = :parentId")
  suspend fun getChildren(parentId: String): List<PlanItem>

  @Query("SELECT COUNT(*) FROM plan_items WHERE parentId = :parentId AND archivedAt IS NULL")
  suspend fun countActiveChildren(parentId: String): Int

  @Query("SELECT * FROM plan_items WHERE boardId = :boardId")
  suspend fun getAllItems(boardId: String): List<PlanItem>

  @Query("SELECT COALESCE(MAX(rank), 0) FROM plan_items WHERE boardId = :boardId")
  suspend fun maxItemRank(boardId: String): Long

  @Query(
    "SELECT COALESCE(MAX(rank), 0) FROM plan_items " +
      "WHERE boardId = :boardId AND " +
      "(columnId = :columnId OR (columnId IS NULL AND :columnId IS NULL))"
  )
  suspend fun maxItemRankInColumn(boardId: String, columnId: String?): Long

  @Query("SELECT COALESCE(MAX(rank), 0) FROM plan_boards") suspend fun maxBoardRank(): Long

  @Query("SELECT COALESCE(MAX(rank), 0) FROM plan_columns WHERE boardId = :boardId")
  suspend fun maxColumnRank(boardId: String): Long

  /** Includes archived tasks: hiding a task never makes its board safe to delete. */
  @Query("SELECT COUNT(*) FROM plan_items WHERE boardId = :boardId")
  suspend fun countItemsForBoard(boardId: String): Int

  @Query("SELECT COUNT(*) FROM plan_items WHERE columnId = :columnId")
  suspend fun countItemsForColumn(columnId: String): Int

  @Query(
    "UPDATE plan_items SET columnId = :fallbackColumnId, updatedAt = :updatedAt " +
      "WHERE boardId = :boardId AND columnId = :deletedColumnId"
  )
  suspend fun moveItemsToColumn(
    boardId: String,
    deletedColumnId: String,
    fallbackColumnId: String,
    updatedAt: Long,
  )

  @Query("SELECT COUNT(*) FROM plan_blocks WHERE planItemId = :itemId")
  suspend fun countBlocksForItem(itemId: String): Int

  @Query("SELECT COALESCE(MAX(position), -1) FROM plan_blocks WHERE planItemId = :itemId")
  suspend fun maxBlockPosition(itemId: String): Int

  @Query("SELECT * FROM plan_blocks WHERE id = :id")
  suspend fun getBlock(id: String): PlanBlock?

  @Query("SELECT * FROM plan_blocks WHERE planItemId = :itemId ORDER BY position, id")
  suspend fun getBlocksForItem(itemId: String): List<PlanBlock>

  @Query(
    "SELECT * FROM plan_blocks WHERE startAt < :endExclusive AND endAt > :startInclusive " +
      "ORDER BY startAt, position"
  )
  fun observeBlocksInRange(startInclusive: Long, endExclusive: Long): Flow<List<PlanBlock>>

  @Query(
    "SELECT plan_blocks.* FROM plan_blocks " +
      "INNER JOIN plan_items ON plan_items.id = plan_blocks.planItemId " +
      "WHERE plan_items.boardId = :boardId AND plan_items.archivedAt IS NULL " +
      "ORDER BY plan_blocks.startAt, plan_blocks.position, plan_blocks.id"
  )
  fun observeBlocks(boardId: String): Flow<List<PlanBlock>>

  @Query(
    "SELECT plan_blocks.* FROM plan_blocks " +
      "INNER JOIN plan_items ON plan_items.id = plan_blocks.planItemId " +
      "WHERE plan_items.boardId = :boardId ORDER BY plan_blocks.startAt, plan_blocks.position, plan_blocks.id"
  )
  suspend fun getBlocksForBoard(boardId: String): List<PlanBlock>

  @Query("SELECT * FROM plan_dependencies WHERE predecessorId = :itemId OR successorId = :itemId")
  suspend fun getDependenciesFor(itemId: String): List<PlanDependency>

  @Query("SELECT * FROM plan_dependencies WHERE id = :id")
  suspend fun getDependency(id: String): PlanDependency?

  @Query("SELECT * FROM plan_dependencies WHERE boardId = :boardId")
  suspend fun getDependenciesForBoard(boardId: String): List<PlanDependency>

  @Query("SELECT * FROM plan_dependencies WHERE boardId = :boardId ORDER BY createdAt, id")
  fun observeDependencies(boardId: String): Flow<List<PlanDependency>>

  @Query(
    "SELECT * FROM work_schedules WHERE archivedAt IS NULL " +
      "ORDER BY isDefault DESC, rank, name"
  )
  fun observeWorkSchedules(): Flow<List<WorkSchedule>>

  @Query(
    "SELECT * FROM work_schedules WHERE archivedAt IS NULL " +
      "ORDER BY isDefault DESC, rank, name"
  )
  suspend fun getWorkSchedules(): List<WorkSchedule>

  @Query("SELECT * FROM work_schedules ORDER BY rank, name, id")
  suspend fun getAllWorkSchedules(): List<WorkSchedule>

  @Query("SELECT * FROM work_schedules WHERE id = :id")
  suspend fun getWorkSchedule(id: String): WorkSchedule?

  @Query("SELECT * FROM work_schedules WHERE nameKey = :nameKey LIMIT 1")
  suspend fun getWorkScheduleByNameKey(nameKey: String): WorkSchedule?

  @Query("SELECT COALESCE(MAX(rank), 0) FROM work_schedules")
  suspend fun maxWorkScheduleRank(): Long

  @Query(
    "SELECT * FROM work_schedules WHERE isDefault = 1 AND archivedAt IS NULL " +
      "ORDER BY rank, id LIMIT 1"
  )
  suspend fun getDefaultWorkSchedule(): WorkSchedule?

  @Query(
    "SELECT * FROM work_schedule_windows WHERE scheduleId = :scheduleId " +
      "ORDER BY kind, dayOfWeek, localDate, rank, startMinute, id"
  )
  fun observeWorkScheduleWindows(scheduleId: String): Flow<List<WorkScheduleWindow>>

  @Query(
    "SELECT * FROM work_schedule_windows WHERE scheduleId = :scheduleId " +
      "ORDER BY kind, dayOfWeek, localDate, rank, startMinute, id"
  )
  suspend fun getWorkScheduleWindows(scheduleId: String): List<WorkScheduleWindow>

  @Query("SELECT * FROM plan_item_schedules WHERE planItemId = :itemId")
  suspend fun getPlanItemSchedule(itemId: String): PlanItemSchedule?

  @Query(
    "SELECT * FROM plan_item_schedules WHERE workScheduleId = :workScheduleId " +
      "ORDER BY planItemId"
  )
  suspend fun getPlanItemSchedulesForWorkSchedule(
    workScheduleId: String,
  ): List<PlanItemSchedule>

  @Query(
    "SELECT plan_item_schedules.* FROM plan_item_schedules " +
      "INNER JOIN plan_items ON plan_items.id = plan_item_schedules.planItemId " +
      "WHERE plan_items.boardId = :boardId AND plan_items.archivedAt IS NULL " +
      "ORDER BY plan_item_schedules.planItemId"
  )
  fun observePlanItemSchedules(boardId: String): Flow<List<PlanItemSchedule>>

  @Query(
    "SELECT work_schedules.* FROM work_schedules " +
      "INNER JOIN plan_item_schedules " +
      "ON plan_item_schedules.workScheduleId = work_schedules.id " +
      "WHERE plan_item_schedules.planItemId = :itemId LIMIT 1"
  )
  fun observeWorkScheduleForItem(itemId: String): Flow<WorkSchedule?>

  @Query("SELECT * FROM plan_mutations WHERE id = :id")
  suspend fun getPlanMutation(id: String): PlanMutation?

  @Query(
    "SELECT * FROM plan_mutations " +
      "WHERE ((:boardId IS NULL AND boardId IS NULL) OR boardId = :boardId) " +
      "ORDER BY createdAt DESC, id DESC LIMIT :limit"
  )
  fun observePlanMutations(boardId: String?, limit: Int): Flow<List<PlanMutation>>

  @Query(
    "SELECT * FROM plan_mutations " +
      "WHERE ((:boardId IS NULL AND boardId IS NULL) OR boardId = :boardId) " +
      "AND status = 'applied' AND (expiresAt IS NULL OR expiresAt > :now) " +
      "ORDER BY createdAt DESC, id DESC LIMIT 1"
  )
  suspend fun getLatestUndoableMutation(boardId: String?, now: Long): PlanMutation?

  @Query("SELECT COUNT(*) FROM plan_mutations WHERE boardId = :boardId")
  suspend fun countPlanMutationsForBoard(boardId: String): Int

  @Query(
    "SELECT * FROM saved_views WHERE boardId = :boardId " +
      "ORDER BY pinned DESC, rank, name, id"
  )
  fun observeSavedViews(boardId: String): Flow<List<SavedView>>

  @Query("SELECT COUNT(*) FROM saved_views WHERE boardId = :boardId")
  suspend fun countSavedViewsForBoard(boardId: String): Int

  @Query("SELECT * FROM plan_baselines WHERE boardId = :boardId ORDER BY capturedAt DESC")
  fun observeBaselines(boardId: String): Flow<List<PlanBaseline>>

  @Query("SELECT * FROM plan_baselines WHERE boardId = :boardId ORDER BY capturedAt DESC")
  suspend fun getBaselines(boardId: String): List<PlanBaseline>

  @Query("SELECT * FROM plan_baselines WHERE id = :id")
  suspend fun getBaseline(id: String): PlanBaseline?

  @Query("SELECT * FROM plan_baselines WHERE boardId = :boardId AND nameKey = :nameKey LIMIT 1")
  suspend fun getBaselineByNameKey(boardId: String, nameKey: String): PlanBaseline?

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBaseline(baseline: PlanBaseline)

  @Delete suspend fun deleteBaseline(baseline: PlanBaseline)

  @Query("SELECT * FROM saved_views WHERE id = :id")
  suspend fun getSavedView(id: String): SavedView?

  @Query("SELECT * FROM saved_views WHERE boardId = :boardId ORDER BY pinned DESC, rank, name, id")
  suspend fun getSavedViews(boardId: String): List<SavedView>

  @Query(
    "SELECT * FROM saved_views WHERE boardId = :boardId AND surface = :surface " +
      "AND nameKey = :nameKey LIMIT 1"
  )
  suspend fun getSavedViewByNameKey(
    boardId: String,
    surface: String,
    nameKey: String,
  ): SavedView?

  @Query("SELECT COALESCE(MAX(rank), 0) FROM saved_views WHERE boardId = :boardId")
  suspend fun maxSavedViewRank(boardId: String): Long

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBoard(board: PlanBoard)

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBoards(boards: List<PlanBoard>)

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertColumns(columns: List<PlanColumn>)

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertColumn(column: PlanColumn)

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertItem(item: PlanItem)

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBlock(block: PlanBlock)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertDependency(dependency: PlanDependency)

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSavedView(view: SavedView)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertWorkSchedule(schedule: WorkSchedule)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertWorkScheduleWindow(window: WorkScheduleWindow)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertWorkScheduleWindows(windows: List<WorkScheduleWindow>)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertPlanItemSchedule(schedule: PlanItemSchedule)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertPlanMutation(mutation: PlanMutation)

  @Update suspend fun updateItem(item: PlanItem)

  @Update suspend fun updateItems(items: List<PlanItem>)

  @Update suspend fun updateBoard(board: PlanBoard)

  @Update suspend fun updateColumn(column: PlanColumn)

  @Update suspend fun updateBlock(block: PlanBlock)

  @Update suspend fun updateDependency(dependency: PlanDependency)

  @Update suspend fun updateSavedView(view: SavedView)

  @Update suspend fun updateWorkSchedule(schedule: WorkSchedule)

  @Update suspend fun updateWorkSchedules(schedules: List<WorkSchedule>)

  @Update suspend fun updateWorkScheduleWindow(window: WorkScheduleWindow)

  @Update suspend fun updatePlanItemSchedule(schedule: PlanItemSchedule)

  @Update suspend fun updatePlanItemSchedules(schedules: List<PlanItemSchedule>)

  @Query("DELETE FROM work_schedule_windows WHERE scheduleId = :scheduleId")
  suspend fun deleteWorkScheduleWindows(scheduleId: String)

  /** Mutation payloads are append-only; only one non-expired applied command can win Undo. */
  @Query(
    "UPDATE plan_mutations SET status = 'undone', updatedAt = :undoneAt, " +
      "undoneAt = :undoneAt WHERE id = :id AND status = 'applied' " +
      "AND (expiresAt IS NULL OR expiresAt > :undoneAt)"
  )
  suspend fun markPlanMutationUndone(id: String, undoneAt: Long): Int

  @Query(
    "UPDATE plan_mutations SET status = 'expired', updatedAt = :now " +
      "WHERE status = 'applied' AND expiresAt IS NOT NULL AND expiresAt <= :now"
  )
  suspend fun expirePlanMutations(now: Long): Int

  @Query(
    "DELETE FROM plan_mutations WHERE status IN ('undone', 'expired') AND updatedAt < :cutoff"
  )
  suspend fun pruneTerminalPlanMutations(cutoff: Long): Int

  @Delete suspend fun deleteItem(item: PlanItem)

  @Delete suspend fun deleteBoard(board: PlanBoard)

  @Delete suspend fun deleteColumn(column: PlanColumn)

  @Delete suspend fun deleteBlock(block: PlanBlock)

  @Delete suspend fun deleteDependency(dependency: PlanDependency)

  @Delete suspend fun deleteWorkSchedule(schedule: WorkSchedule)

  @Delete suspend fun deleteWorkScheduleWindow(window: WorkScheduleWindow)

  @Delete suspend fun deletePlanItemSchedule(schedule: PlanItemSchedule)

  @Delete suspend fun deleteSavedView(view: SavedView)
}
