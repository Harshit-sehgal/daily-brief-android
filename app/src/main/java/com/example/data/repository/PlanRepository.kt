package com.example.data.repository

import com.example.data.model.PlanBaseline
import com.example.core.PlanProposal
import com.example.data.prefs.UndoWindowPolicy
import android.content.Context
import androidx.room.withTransaction
import com.example.core.WorkingCalendar
import com.example.core.WorkingCalendarSpec
import com.example.core.PlanBlockPreview
import com.example.core.WorkingInterval
import com.example.data.database.AppDatabase
import com.example.data.database.LegacyPlanCatalogBuilder
import com.example.data.database.PlanItemWithBlocks
import com.example.data.model.PlanBlock
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import com.example.data.model.PlanItemSchedule
import com.example.data.model.PlanMutation
import com.example.data.model.PlanMutationOrigin
import com.example.data.model.PlanMutationStatus
import com.example.data.model.PlanPriority
import com.example.data.model.PlanSchedulingMode
import com.example.data.model.SavedView
import com.example.data.model.SystemSetting
import com.example.data.model.WorkSchedule
import com.example.data.model.WorkScheduleWindow
import com.example.data.prefs.SettingKeys
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PlanItemInput(
  val id: String? = null,
  val boardId: String,
  val columnId: String? = null,
  val parentId: String? = null,
  val title: String,
  val notes: String = "",
  val startConstraint: Long? = null,
  val dueAt: Long? = null,
  val effortMinutes: Int? = null,
  val progress: Int = 0,
  val priority: String = PlanPriority.NORMAL,
  val owner: String = "",
  val schedulingMode: String = PlanSchedulingMode.AUTO,
  val locked: Boolean = false,
  val isMilestone: Boolean = false,
)

data class PlanBlockInput(
  val id: String? = null,
  val planItemId: String,
  val startAt: Long,
  val endAt: Long,
  /** Null appends after the task's existing split blocks. */
  val position: Int? = null,
  val locked: Boolean = false,
  val linkedEventId: String? = null,
)

data class PlanMoveResult(val movedItem: PlanItem, val movedCount: Int)

data class PlanAtomicMoveResult(
  val requestedItemIds: Set<String>,
  /** Fresh post-command rows for every hierarchy included by the selection. */
  val items: List<PlanItem>,
  val movedItemIds: Set<String>,
  val alreadyAtDestinationItemIds: Set<String>,
) {
  val movedCount: Int
    get() = movedItemIds.size

  val includedHierarchyItemIds: Set<String>
    get() = items.mapTo(linkedSetOf(), PlanItem::id) - requestedItemIds
}

data class JournaledPlanResult<T>(val value: T, val mutationId: String?)

enum class PlanUndoStatus {
  UNDONE,
  UNAVAILABLE,
  EXPIRED,
  STALE,
  UNSUPPORTED,
}

data class PlanUndoResult(val status: PlanUndoStatus, val summary: String? = null)

data class SavedPlanView(val view: SavedView, val state: SavedPlanViewState)

data class WorkingScheduleArchiveResult(
  val archivedSchedule: WorkSchedule,
  val replacementSchedule: WorkSchedule?,
  val reroutedAssignmentCount: Int,
)

/**
 * The ownership boundary for flexible work. Nothing here writes a BriefingEvent,
 * so synced commitments cannot accidentally become draggable planning records.
 */
class PlanRepository internal constructor(private val database: AppDatabase) {
  constructor(context: Context) : this(AppDatabase.getDatabase(context))

  private val planDao = database.planDao()
  private val eventDao = database.eventDao()
  private val settingDao = database.settingDao()
  private val catalogMutex = Mutex()

  fun observeBoards(): Flow<List<PlanBoard>> = planDao.observeBoards()

  fun observeActiveBoardId(): Flow<String?> =
    settingDao.getSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID).map { it?.value }

  fun observeColumns(boardId: String): Flow<List<PlanColumn>> = planDao.observeColumns(boardId)

  fun observeItems(boardId: String): Flow<List<PlanItem>> = planDao.observeItems(boardId)

  fun observeBlocks(boardId: String): Flow<List<PlanBlock>> = planDao.observeBlocks(boardId)

  /** One-shot reads for cross-board work, where observing every board would cost more than it says. */
  suspend fun itemsForBoardOnce(boardId: String): List<PlanItem> =
    withContext(Dispatchers.IO) { planDao.getAllItems(boardId) }

  suspend fun blocksForBoardOnce(boardId: String): List<PlanBlock> =
    withContext(Dispatchers.IO) { planDao.getBlocksForBoard(boardId) }

  fun observeDependencies(boardId: String): Flow<List<PlanDependency>> =
    planDao.observeDependencies(boardId)

  fun observeGanttItems(boardId: String): Flow<List<PlanItemWithBlocks>> =
    planDao.observeItemsWithBlocks(boardId)

  fun observeItemScheduleAssignments(boardId: String): Flow<List<PlanItemSchedule>> =
    planDao.observePlanItemSchedules(boardId)

  /**
   * Emits every active schedule whose complete window set passes strict mapping. An invalid
   * alternate is omitted rather than offered as a fake choice; an item explicitly mapped to that
   * schedule still resolves as unavailable and never falls back to the default.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observeActiveWorkingCalendars(): Flow<List<PersistedWorkingCalendar>> =
    planDao.observeWorkSchedules().flatMapLatest { schedules ->
      if (schedules.isEmpty()) flowOf(emptyList())
      else {
        combine(
          schedules.map { schedule ->
            planDao.observeWorkScheduleWindows(schedule.id).map { windows ->
              runCatching { WorkingCalendarMapper.fromEntities(schedule, windows) }.getOrNull()
            }
          }
        ) { calendars -> calendars.filterNotNull() }
      }
    }

  /** Malformed or future saved-view payloads are omitted as a whole. */
  fun observeSavedViews(boardId: String): Flow<List<SavedPlanView>> =
    planDao.observeSavedViews(boardId).map { views ->
      views.mapNotNull { view -> SavedViewCodec.decode(view)?.let { SavedPlanView(view, it) } }
    }

  suspend fun saveSavedView(
    boardId: String,
    name: String,
    state: SavedPlanViewState,
  ): SavedPlanView =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val board = planDao.getBoard(boardId)
        require(board != null && board.archivedAt == null) { "That plan board is unavailable" }
        val cleanName = requireViewName(name)
        val encoded = SavedViewCodec.encode(state)
        val nameKey = LegacyPlanCatalogBuilder.nameKey(cleanName)
        require(planDao.getSavedViewByNameKey(boardId, state.surface, nameKey) == null) {
          "A saved ${state.surface} view with that name already exists"
        }
        val now = System.currentTimeMillis()
        val view =
          SavedView(
            id = "view_${UUID.randomUUID()}",
            boardId = boardId,
            name = cleanName,
            nameKey = nameKey,
            surface = state.surface,
            filtersJson = encoded.filtersJson,
            grouping = encoded.grouping,
            sortJson = encoded.sortJson,
            columnsJson = encoded.columnsJson,
            rangeDays = encoded.rangeDays,
            zoom = encoded.zoom,
            collapsedIdsJson = encoded.collapsedIdsJson,
            rank = planDao.maxSavedViewRank(boardId) + RANK_GAP,
            createdAt = now,
            updatedAt = now,
          )
        planDao.insertSavedView(view)
        SavedPlanView(view, state)
      }
    }

  /** Renames a view without touching what it shows. */
  suspend fun renameSavedView(id: String, boardId: String, name: String): SavedPlanView =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val view = requireNotNull(planDao.getSavedView(id)) { "That saved view is unavailable" }
        require(view.boardId == boardId) { "That saved view belongs to another plan board" }
        val cleanName = requireViewName(name)
        val nameKey = LegacyPlanCatalogBuilder.nameKey(cleanName)
        val clash = planDao.getSavedViewByNameKey(boardId, view.surface, nameKey)
        require(clash == null || clash.id == view.id) {
          "A saved ${view.surface} view with that name already exists"
        }
        val renamed = view.copy(name = cleanName, nameKey = nameKey, updatedAt = System.currentTimeMillis())
        planDao.updateSavedView(renamed)
        SavedPlanView(renamed, requireNotNull(SavedViewCodec.decode(renamed)))
      }
    }

  /** Points an existing view at what is on screen now, keeping its name, rank and pin. */
  suspend fun updateSavedView(id: String, boardId: String, state: SavedPlanViewState): SavedPlanView =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val view = requireNotNull(planDao.getSavedView(id)) { "That saved view is unavailable" }
        require(view.boardId == boardId) { "That saved view belongs to another plan board" }
        val encoded = SavedViewCodec.encode(state)
        val updated =
          view.copy(
            surface = state.surface,
            filtersJson = encoded.filtersJson,
            grouping = encoded.grouping,
            sortJson = encoded.sortJson,
            columnsJson = encoded.columnsJson,
            rangeDays = encoded.rangeDays,
            zoom = encoded.zoom,
            collapsedIdsJson = encoded.collapsedIdsJson,
            updatedAt = System.currentTimeMillis(),
          )
        // A surface change could collide with a view already named this on the new surface.
        val clash = planDao.getSavedViewByNameKey(boardId, updated.surface, updated.nameKey)
        require(clash == null || clash.id == updated.id) {
          "A saved ${updated.surface} view with that name already exists"
        }
        planDao.updateSavedView(updated)
        SavedPlanView(updated, state)
      }
    }

  /** Copies a view so an experiment does not cost the original. */
  suspend fun duplicateSavedView(id: String, boardId: String): SavedPlanView =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val view = requireNotNull(planDao.getSavedView(id)) { "That saved view is unavailable" }
        require(view.boardId == boardId) { "That saved view belongs to another plan board" }
        val state = requireNotNull(SavedViewCodec.decode(view)) {
          "That saved view is invalid and was not copied"
        }
        val copyName = availableCopyName(boardId, view)
        val now = System.currentTimeMillis()
        val copy =
          view.copy(
            id = "view_${UUID.randomUUID()}",
            name = copyName,
            nameKey = LegacyPlanCatalogBuilder.nameKey(copyName),
            pinned = false,
            rank = planDao.maxSavedViewRank(boardId) + RANK_GAP,
            createdAt = now,
            updatedAt = now,
          )
        planDao.insertSavedView(copy)
        SavedPlanView(copy, state)
      }
    }

  /**
   * Pins at most one view per board, so "the view this board opens with" is never ambiguous.
   * Pinning the pinned view unpins it.
   */
  suspend fun setSavedViewPinned(id: String, boardId: String, pinned: Boolean): SavedPlanView =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val view = requireNotNull(planDao.getSavedView(id)) { "That saved view is unavailable" }
        require(view.boardId == boardId) { "That saved view belongs to another plan board" }
        val now = System.currentTimeMillis()
        if (pinned) {
          planDao.getSavedViews(boardId).filter { it.pinned && it.id != view.id }.forEach {
            planDao.updateSavedView(it.copy(pinned = false, updatedAt = now))
          }
        }
        val updated = view.copy(pinned = pinned, updatedAt = now)
        planDao.updateSavedView(updated)
        SavedPlanView(updated, requireNotNull(SavedViewCodec.decode(updated)))
      }
    }

  private fun requireViewName(name: String): String {
    val cleanName = name.trim()
    require(cleanName.isNotEmpty() && cleanName.length <= 80 && cleanName.none(Char::isISOControl)) {
      "A saved view needs a name of 80 characters or fewer"
    }
    return cleanName
  }

  private suspend fun availableCopyName(boardId: String, view: SavedView): String {
    val base = view.name.take(70)
    (2..99).forEach { attempt ->
      val candidate = "$base ($attempt)"
      if (planDao.getSavedViewByNameKey(boardId, view.surface, LegacyPlanCatalogBuilder.nameKey(candidate)) == null) {
        return candidate
      }
    }
    error("That view has too many copies already")
  }

  fun observeBaselines(boardId: String): Flow<List<PlanBaseline>> = planDao.observeBaselines(boardId)

  /**
   * Captures what the plan looks like right now, under a name.
   *
   * The snapshot is encoded with the same codec the journal uses, so a baseline stays readable after
   * the tasks it describes are edited or archived — a baseline that decayed with the plan would
   * measure nothing.
   */
  suspend fun captureBaseline(boardId: String, name: String): PlanBaseline =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val board = requireNotNull(planDao.getBoard(boardId)) { "That Plan is unavailable" }
        require(board.archivedAt == null) { "That Plan is unavailable" }
        val cleanName = requireViewName(name)
        val nameKey = LegacyPlanCatalogBuilder.nameKey(cleanName)
        require(planDao.getBaselineByNameKey(boardId, nameKey) == null) {
          "A baseline with that name already exists"
        }
        val items = planDao.getAllItems(boardId).filter { it.archivedAt == null }
        val itemIds = items.mapTo(mutableSetOf()) { it.id }
        val blocks = planDao.getBlocksForBoard(boardId).filter { it.planItemId in itemIds }
        val baseline =
          PlanBaseline(
            id = "baseline_${UUID.randomUUID()}",
            boardId = boardId,
            name = cleanName,
            nameKey = nameKey,
            capturedAt = System.currentTimeMillis(),
            itemsJson = PlanBaselineCodec.encodeItems(items),
            blocksJson = PlanBaselineCodec.encodeBlocks(blocks),
          )
        planDao.insertBaseline(baseline)
        baseline
      }
    }

  /**
   * One side of a replacement, over a fixed set of ids.
   *
   * Ids the side does not hold are recorded as absent rather than omitted, so both sides of the
   * command describe the same targets — the journal's rule — and Undo knows what to remove. Null
   * means "this side holds nothing at all", which only a single-target command can say: the caller
   * turns that into a create or a delete.
   */
  private fun blockStateOver(union: List<String>, held: List<PlanBlock>): PlanMutationState? {
    val heldById = held.associateBy(PlanBlock::id)
    val present = union.mapNotNull { heldById[it]?.toMutationState() }
    val absent = union.filterNot(heldById::containsKey)
    return when {
      union.isEmpty() -> null
      union.size == 1 -> present.singleOrNull()
      else -> PlanBlockGroupState(blocks = present, absentIds = absent)
    }
  }

  suspend fun deleteBaseline(id: String, boardId: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val baseline = planDao.getBaseline(id)
        if (baseline == null || baseline.boardId != boardId) return@withTransaction false
        planDao.deleteBaseline(baseline)
        true
      }
    }

  /** The baseline's own tasks and blocks, or null when the record cannot be read. */
  suspend fun readBaseline(id: String): Pair<List<PlanItem>, List<PlanBlock>>? =
    withContext(Dispatchers.IO) {
      val baseline = planDao.getBaseline(id) ?: return@withContext null
      runCatching {
          PlanBaselineCodec.decodeItems(baseline.itemsJson) to
            PlanBaselineCodec.decodeBlocks(baseline.blocksJson)
        }
        .getOrNull()
    }

  /**
   * Puts the schedule back to a baseline, as one journaled command.
   *
   * Only blocks are restored — the schedule is what a baseline is for. Task titles, notes and
   * progress are today's work and are deliberately left alone, because restoring them would quietly
   * undo real progress in the name of a snapshot.
   */
  suspend fun restoreBaselineWithUndo(id: String, boardId: String): JournaledPlanResult<Int> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val baseline = requireNotNull(planDao.getBaseline(id)) { "That baseline is unavailable" }
        require(baseline.boardId == boardId) { "That baseline belongs to another Plan" }
        val restoredBlocks =
          requireNotNull(runCatching { PlanBaselineCodec.decodeBlocks(baseline.blocksJson) }.getOrNull()) {
            "That baseline is unreadable and was not restored"
          }
        val liveItemIds =
          planDao.getAllItems(boardId).filter { it.archivedAt == null }.mapTo(mutableSetOf()) { it.id }
        val applicable = restoredBlocks.filter { it.planItemId in liveItemIds }
        val currentBlocks = planDao.getBlocksForBoard(boardId).filter { it.planItemId in liveItemIds }
        require(applicable.isNotEmpty() || currentBlocks.isNotEmpty()) {
          "There is nothing to restore"
        }

        val now = System.currentTimeMillis()
        val restored = applicable.map { it.copy(updatedAt = now) }
        // A restore replaces a set of rows, and the two sides rarely name the same ones: a block
        // deleted since the baseline exists on one side only. Both states therefore span the union
        // of ids, with the ids each side does not have recorded as absent — that is what makes one
        // Undo able to put the schedule back exactly, rather than leaving both versions behind.
        val union = (currentBlocks.map { it.id } + restored.map { it.id }).distinct()
        val before = blockStateOver(union, currentBlocks)
        val after = blockStateOver(union, restored)

        currentBlocks.forEach { planDao.deleteBlock(it) }
        restored.forEach { block -> planDao.insertBlock(block) }

        val mutationId =
          insertMutation(
            boardId = boardId,
            // One block cleared to nothing, or made where there was none, is a delete or a create;
            // only a genuine two-sided replacement is an edit. The journal's null-side rules decide
            // which inverse Undo runs, so the type has to describe what actually happened.
            mutationType =
              when {
                before == null -> PlanMutationType.BLOCK_CREATE
                after == null -> PlanMutationType.BLOCK_DELETE
                else -> PlanMutationType.BLOCK_EDIT
              },
            targetType =
              if (union.size == 1) PlanMutationTarget.BLOCK else PlanMutationTarget.BLOCK_GROUP,
            summary = "Restored the schedule from \"${'$'}{baseline.name}\"",
            before = before,
            after = after,
            now = now,
          )
        JournaledPlanResult(restored.size, mutationId)
      }
    }

  suspend fun deleteSavedView(id: String, boardId: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val view = planDao.getSavedView(id)
        if (view == null || view.boardId != boardId) return@withTransaction false
        planDao.deleteSavedView(view)
        if (
          settingDao.getSettingSync(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID)?.value == view.id
        ) {
          settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID, ""))
        }
        true
      }
    }

  suspend fun activateSavedView(id: String, boardId: String): SavedPlanView =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val view = requireNotNull(planDao.getSavedView(id)) { "That saved view is unavailable" }
        require(view.boardId == boardId) { "That saved view belongs to another plan board" }
        val state = requireNotNull(SavedViewCodec.decode(view)) {
          "That saved view is invalid and was not applied"
        }
        val surfaceLabel =
          when (state.surface) {
            com.example.data.model.PlanSurface.OUTLINE -> "Outline"
            com.example.data.model.PlanSurface.BOARD -> "Board"
            com.example.data.model.PlanSurface.GANTT -> "Gantt"
            else -> error("Saved view surface was validated")
          }
        settingDao.insertSetting(SystemSetting(SettingKeys.PLAN_VIEW, surfaceLabel))
        state.rangeDays?.let { days ->
          settingDao.insertSetting(SystemSetting(SettingKeys.GANTT_RANGE_DAYS, days.toString()))
        }
        // Presentation only. A view restores how work is shown and never what the work is.
        settingDao.insertSetting(
          SystemSetting(SettingKeys.PLAN_OUTLINE_SORT, state.sort.firstOrNull() ?: "manual")
        )
        settingDao.insertSetting(
          SystemSetting(
            SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED,
            (state.filters[SavedPlanViewState.FILTER_HIDE_COMPLETED] == "true").toString(),
          )
        )
        settingDao.insertSetting(
          SystemSetting(
            SettingKeys.PLAN_OUTLINE_COLLAPSED,
            SettingKeys.encodeList(state.collapsedItemIds.sorted()),
          )
        )
        settingDao.insertSetting(SystemSetting(SettingKeys.PLAN_OUTLINE_GROUPING, state.grouping))
        settingDao.insertSetting(
          SystemSetting(SettingKeys.PLAN_BOARD_COLUMNS, SettingKeys.encodeList(state.columns))
        )
        settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID, view.id))
        SavedPlanView(view, state)
      }
    }

  /**
   * Emits the active default and all its windows as one validated value. Invalid persisted data
   * fails the complete mapping instead of exposing a partially usable calendar.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observeDefaultWorkingCalendar(): Flow<PersistedWorkingCalendar?> =
    planDao.observeWorkSchedules().flatMapLatest { schedules ->
      val defaults = schedules.filter { it.isDefault }
      require(defaults.size <= 1) { "Only one active default working schedule is allowed" }
      val schedule = defaults.singleOrNull()
      if (schedule == null) flowOf(null)
      else {
        planDao.observeWorkScheduleWindows(schedule.id).map { windows ->
          WorkingCalendarMapper.fromEntities(schedule, windows)
        }
      }
    }

  /** Fresh installs do not run migrations, so they receive the same deterministic seeds here. */
  suspend fun ensureCatalog() =
    withContext(Dispatchers.IO) {
      catalogMutex.withLock {
        database.withTransaction {
          val now = System.currentTimeMillis()
          var boards = planDao.getBoards()
          if (boards.isEmpty()) {
            val catalog =
              LegacyPlanCatalogBuilder.build(
                encodedBoards = null,
                activeBoardName = null,
                encodedColumns = { null },
                eventDestinations = emptyList(),
              )
            planDao.insertBoards(
              catalog.boards.map { legacy ->
                PlanBoard(
                  id = legacy.id,
                  name = legacy.name,
                  nameKey = legacy.nameKey,
                  rank = legacy.rank,
                  isDefault = legacy.isDefault,
                  createdAt = now,
                  updatedAt = now,
                )
              }
            )
            planDao.insertColumns(
              catalog.boards.flatMap { board ->
                board.columns.map { legacy ->
                  PlanColumn(
                    id = legacy.id,
                    boardId = legacy.boardId,
                    name = legacy.name,
                    nameKey = legacy.nameKey,
                    rank = legacy.rank,
                    createdAt = now,
                    updatedAt = now,
                  )
                }
              }
            )
            settingDao.insertSetting(
              SystemSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, catalog.activeBoardId)
            )
            boards = planDao.getBoards()
          }

          val stored = settingDao.getSettingSync(SettingKeys.ACTIVE_PLAN_BOARD_ID)?.value
          if (boards.none { it.id == stored }) {
            val fallback = boards.firstOrNull { it.isDefault } ?: boards.first()
            settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, fallback.id))
          }

          var defaultSchedules = planDao.getWorkSchedules().filter { it.isDefault }
          require(defaultSchedules.size <= 1) {
            "Only one active default working schedule is allowed"
          }
          if (defaultSchedules.isEmpty()) {
            val seeded =
              WorkingCalendarMapper.defaultSchedule(
                timeZoneId = TimeZone.getDefault().id,
                now = now,
              )
            planDao.insertWorkSchedule(seeded.schedule)
            planDao.insertWorkScheduleWindows(seeded.windows)
            defaultSchedules = listOf(seeded.schedule)
          }
          val defaultSchedule = defaultSchedules.single()
          WorkingCalendarMapper.fromEntities(
            defaultSchedule,
            planDao.getWorkScheduleWindows(defaultSchedule.id),
          )
        }
      }
    }

  /**
   * Replaces the default schedule and all its windows atomically after validating the complete
   * resulting pure spec. The supplied time-zone ID is persisted verbatim and never inferred here.
   */
  suspend fun saveDefaultWorkingCalendar(
    spec: WorkingCalendarSpec,
  ): PersistedWorkingCalendar = saveDefaultWorkingCalendarWithUndo(spec).value

  suspend fun saveDefaultWorkingCalendarWithUndo(
    spec: WorkingCalendarSpec,
  ): JournaledPlanResult<PersistedWorkingCalendar> {
    WorkingCalendar.validate(spec)
    ensureCatalog()
    return withContext(Dispatchers.IO) {
      database.withTransaction {
        val defaultSchedule = requireWorkingScheduleCatalogInvariant()
        updateWorkingScheduleInTransaction(
          existing = defaultSchedule,
          name = defaultSchedule.name,
          spec = spec,
          now = System.currentTimeMillis(),
        )
      }
    }
  }

  suspend fun createWorkingSchedule(
    name: String,
    spec: WorkingCalendarSpec,
  ): PersistedWorkingCalendar = createWorkingScheduleWithUndo(name, spec).value

  /** Creates one validated, active alternate without changing the sole default. */
  suspend fun createWorkingScheduleWithUndo(
    name: String,
    spec: WorkingCalendarSpec,
  ): JournaledPlanResult<PersistedWorkingCalendar> {
    val (cleanName, nameKey) = canonicalWorkingScheduleName(name)
    WorkingCalendar.validate(spec)
    ensureCatalog()
    return withContext(Dispatchers.IO) {
      database.withTransaction {
        requireWorkingScheduleCatalogInvariant()
        require(planDao.getWorkScheduleByNameKey(nameKey) == null) {
          "A working schedule with that name already exists"
        }
        val now = System.currentTimeMillis()
        val scheduleId = "schedule_${UUID.randomUUID()}"
        val before =
          captureWorkingScheduleState(
            scopeId = scheduleId,
            scheduleIds = setOf(scheduleId),
            assignmentScheduleIds = setOf(scheduleId),
          )
        val created =
          WorkingCalendarMapper.forUpdate(
            schedule =
              WorkSchedule(
                id = scheduleId,
                name = cleanName,
                nameKey = nameKey,
                timeZoneId = spec.zoneId,
                isDefault = false,
                rank = planDao.maxWorkScheduleRank() + RANK_GAP,
                createdAt = now,
                updatedAt = now,
              ),
            spec = spec,
            updatedAt = now,
          )
        planDao.insertWorkSchedule(created.schedule)
        if (created.windows.isNotEmpty()) planDao.insertWorkScheduleWindows(created.windows)
        requireWorkingScheduleCatalogInvariant()
        val after =
          captureWorkingScheduleState(
            scopeId = scheduleId,
            scheduleIds = setOf(scheduleId),
            assignmentScheduleIds = setOf(scheduleId),
          )
        val mutationId =
          insertMutation(
            boardId = null,
            mutationType = PlanMutationType.WORK_SCHEDULE_CREATE,
            targetType = PlanMutationTarget.WORK_SCHEDULE,
            summary = "Created working schedule $cleanName",
            before = before,
            after = after,
            now = now,
          )
        JournaledPlanResult(created, mutationId)
      }
    }
  }

  suspend fun updateWorkingSchedule(
    id: String,
    name: String,
    spec: WorkingCalendarSpec,
  ): PersistedWorkingCalendar = updateWorkingScheduleWithUndo(id, name, spec).value

  /** Renames and replaces one active schedule as one exact journaled command. */
  suspend fun updateWorkingScheduleWithUndo(
    id: String,
    name: String,
    spec: WorkingCalendarSpec,
  ): JournaledPlanResult<PersistedWorkingCalendar> {
    canonicalWorkingScheduleName(name)
    WorkingCalendar.validate(spec)
    ensureCatalog()
    return withContext(Dispatchers.IO) {
      database.withTransaction {
        requireWorkingScheduleCatalogInvariant()
        val existing = requireNotNull(planDao.getWorkSchedule(id)) {
          "That working schedule is unavailable"
        }
        updateWorkingScheduleInTransaction(
          existing = existing,
          name = name,
          spec = spec,
          now = System.currentTimeMillis(),
        )
      }
    }
  }

  suspend fun makeWorkingScheduleDefault(
    id: String,
  ): PersistedWorkingCalendar = makeWorkingScheduleDefaultWithUndo(id).value

  /** Transfers the default flag atomically; task assignments and schedule windows stay untouched. */
  suspend fun makeWorkingScheduleDefaultWithUndo(
    id: String,
  ): JournaledPlanResult<PersistedWorkingCalendar> {
    ensureCatalog()
    return withContext(Dispatchers.IO) {
      database.withTransaction {
        val currentDefault = requireWorkingScheduleCatalogInvariant()
        val target = requireNotNull(planDao.getWorkSchedule(id)) {
          "That working schedule is unavailable"
        }
        require(target.archivedAt == null) { "That working schedule is unavailable" }
        val targetCalendar =
          WorkingCalendarMapper.fromEntities(target, planDao.getWorkScheduleWindows(target.id))
        if (target.id == currentDefault.id) {
          return@withTransaction JournaledPlanResult(targetCalendar, null)
        }

        val affectedIds = setOf(currentDefault.id, target.id)
        val before = captureWorkingScheduleState(target.id, affectedIds)
        val now = System.currentTimeMillis()
        val previous = currentDefault.copy(isDefault = false, updatedAt = now)
        val replacement = target.copy(isDefault = true, updatedAt = now)
        planDao.updateWorkSchedules(listOf(previous, replacement))
        requireWorkingScheduleCatalogInvariant()
        val after = captureWorkingScheduleState(target.id, affectedIds)
        val mutationId =
          insertMutation(
            boardId = null,
            mutationType = PlanMutationType.WORK_SCHEDULE_DEFAULT,
            targetType = PlanMutationTarget.WORK_SCHEDULE,
            summary = "Made ${target.name} the default working schedule",
            before = before,
            after = after,
            now = now,
          )
        JournaledPlanResult(
          WorkingCalendarMapper.fromEntities(
            replacement,
            planDao.getWorkScheduleWindows(replacement.id),
          ),
          mutationId,
        )
      }
    }
  }

  suspend fun archiveWorkingSchedule(
    id: String,
    replacementScheduleId: String? = null,
  ): WorkingScheduleArchiveResult =
    archiveWorkingScheduleWithUndo(id, replacementScheduleId).value

  /**
   * Archives without deleting history or windows. A replacement is mandatory when the schedule is
   * default or assigned; assigned rows are rerouted atomically and a default replacement is
   * promoted in the same transaction. Omitting that explicit replacement fails closed.
   */
  suspend fun archiveWorkingScheduleWithUndo(
    id: String,
    replacementScheduleId: String? = null,
  ): JournaledPlanResult<WorkingScheduleArchiveResult> {
    ensureCatalog()
    return withContext(Dispatchers.IO) {
      database.withTransaction {
        requireWorkingScheduleCatalogInvariant()
        val schedule = requireNotNull(planDao.getWorkSchedule(id)) {
          "That working schedule is unavailable"
        }
        require(schedule.archivedAt == null) { "That working schedule is unavailable" }
        WorkingCalendarMapper.fromEntities(schedule, planDao.getWorkScheduleWindows(schedule.id))
        val assignments = planDao.getPlanItemSchedulesForWorkSchedule(schedule.id)
        val replacement =
          replacementScheduleId?.let { targetId ->
            require(targetId != schedule.id) { "A schedule cannot replace itself" }
            requireNotNull(planDao.getWorkSchedule(targetId)) {
                "The replacement working schedule is unavailable"
              }
              .also { target ->
                require(target.archivedAt == null) {
                  "The replacement working schedule is unavailable"
                }
                WorkingCalendarMapper.fromEntities(
                  target,
                  planDao.getWorkScheduleWindows(target.id),
                )
              }
          }
        require(!schedule.isDefault || replacement != null) {
          "Choose the next default before archiving the default schedule"
        }
        require(assignments.isEmpty() || replacement != null) {
          "Reassign this schedule's tasks before archiving it"
        }

        val affectedIds = buildSet {
          add(schedule.id)
          replacement?.id?.let { add(it) }
        }
        val assignmentScopes = buildSet {
          add(schedule.id)
          if (assignments.isNotEmpty()) replacement?.id?.let { add(it) }
        }
        val before =
          captureWorkingScheduleState(schedule.id, affectedIds, assignmentScopes)
        val now = System.currentTimeMillis()
        val rerouted =
          if (assignments.isEmpty()) emptyList()
          else
            assignments.map { assignment ->
              assignment.copy(
                workScheduleId = requireNotNull(replacement).id,
                updatedAt = now,
              )
            }
        if (rerouted.isNotEmpty()) planDao.updatePlanItemSchedules(rerouted)

        val archived = schedule.copy(isDefault = false, archivedAt = now, updatedAt = now)
        val promoted =
          replacement?.takeIf { schedule.isDefault }?.copy(isDefault = true, updatedAt = now)
        planDao.updateWorkSchedules(listOfNotNull(archived, promoted))
        requireWorkingScheduleCatalogInvariant()
        val after = captureWorkingScheduleState(schedule.id, affectedIds, assignmentScopes)
        val mutationId =
          insertMutation(
            boardId = null,
            mutationType = PlanMutationType.WORK_SCHEDULE_ARCHIVE,
            targetType = PlanMutationTarget.WORK_SCHEDULE,
            summary = "Archived working schedule ${schedule.name}",
            before = before,
            after = after,
            now = now,
          )
        JournaledPlanResult(
          WorkingScheduleArchiveResult(
            archivedSchedule = archived,
            replacementSchedule = promoted ?: replacement,
            reroutedAssignmentCount = rerouted.size,
          ),
          mutationId,
        )
      }
    }
  }

  suspend fun setActiveBoard(boardId: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        // Validate inside the same transaction that publishes both legacy and
        // stable selections. A concurrent delete must win wholly or wait; it
        // must never leave an active ID pointing at a board that disappeared.
        val board = planDao.getBoard(boardId)
        if (board == null || board.archivedAt != null) return@withTransaction false
        val legacyBoards =
          SettingKeys.decodeList(settingDao.getSettingSync(SettingKeys.BOARDS)?.value)
            ?: listOf(SettingKeys.DEFAULT_BOARD)
        if (legacyBoards.none { it == board.name }) {
          settingDao.insertSetting(
            SystemSetting(SettingKeys.BOARDS, SettingKeys.encodeList(legacyBoards + board.name))
          )
        }
        if (settingDao.getSettingSync(SettingKeys.columnsForBoard(board.name)) == null) {
          val columnNames =
            planDao.getColumns(board.id).map { it.name }.ifEmpty { SettingKeys.DEFAULT_COLUMNS }
          settingDao.insertSetting(
            SystemSetting(
              SettingKeys.columnsForBoard(board.name),
              SettingKeys.encodeList(columnNames),
            )
          )
        }
        settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId))
        settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_BOARD, board.name))
        true
      }
    }

  suspend fun saveItem(input: PlanItemInput): PlanItem = saveItemWithUndo(input).value

  suspend fun saveItemWithUndo(input: PlanItemInput): JournaledPlanResult<PlanItem> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val title = input.title.trim()
        require(title.isNotEmpty()) { "A task needs a title" }
        require(input.progress in 0..100) { "Progress must be between 0 and 100" }
        require(input.priority in PlanPriority.All) { "Unknown task priority" }
        require(input.schedulingMode in PlanSchedulingMode.All) { "Unknown scheduling mode" }
        require(input.effortMinutes == null || input.effortMinutes > 0) {
          "Effort must be a positive number of minutes"
        }
        require(!input.isMilestone || input.effortMinutes == null) {
          "Milestones cannot carry an effort estimate"
        }

        val board = planDao.getBoard(input.boardId)
        require(board != null && board.archivedAt == null) { "That plan board is unavailable" }
        val column = input.columnId?.let { planDao.getColumn(it) }
        require(
          input.columnId == null ||
            (column != null && column.boardId == board.id && column.archivedAt == null)
        ) {
          "That workflow column does not belong to this board"
        }

        val existing = input.id?.let { planDao.getItem(it) }
        require(input.id == null || existing != null) { "That task no longer exists" }
        require(existing == null || existing.boardId == board.id) {
          "Moving a task between boards is not available; move its whole project instead"
        }
        require(
          !input.isMilestone || existing == null || planDao.countBlocksForItem(existing.id) == 0
        ) {
          "Remove this task's scheduled blocks before converting it to a milestone"
        }
        val destinationChanged = existing != null && existing.columnId != column?.id
        require(!destinationChanged || existing.parentId == input.parentId) {
          "Change a task's hierarchy and workflow destination separately"
        }
        val movingHierarchy =
          if (destinationChanged) {
            hierarchyComponent(planDao.getAllItems(board.id), existing.id)
          } else {
            emptyList()
          }
        val movingHierarchyIds = movingHierarchy.mapTo(mutableSetOf()) { it.id }
        val parent = input.parentId?.let { planDao.getItem(it) }
        require(input.parentId == null || (parent != null && parent.boardId == board.id)) {
          "A parent task must be on the same board"
        }
        require(
          parent == null || parent.columnId == column?.id || parent.id in movingHierarchyIds
        ) {
          "A parent and its subtasks must stay in the same workflow section"
        }
        require(parent == null || parent.id != existing?.id) { "A task cannot be its own parent" }
        if (existing != null && parent != null) {
          require(!wouldCreateParentCycle(existing.id, parent.id)) {
            "That hierarchy would create a cycle"
          }
        }
        val now = System.currentTimeMillis()
        val companions =
          if (destinationChanged) {
            movingHierarchy
              .filterNot { it.id == existing.id }
              .map { it.copy(columnId = column?.id, updatedAt = now) }
          } else {
            emptyList()
          }
        if (companions.isNotEmpty()) planDao.updateItems(companions)
        val item =
          PlanItem(
            id = existing?.id ?: "task_${UUID.randomUUID()}",
            boardId = board.id,
            columnId = column?.id,
            parentId = parent?.id,
            title = title,
            notes = input.notes.trim().takeIf(String::isNotEmpty),
            rank = existing?.rank ?: planDao.maxItemRank(board.id) + RANK_GAP,
            startConstraint = input.startConstraint,
            dueAt = input.dueAt,
            effortMinutes = input.effortMinutes,
            progress = input.progress,
            priority = input.priority,
            owner = input.owner.trim().takeIf(String::isNotEmpty),
            schedulingMode = input.schedulingMode,
            locked = input.locked,
            isMilestone = input.isMilestone,
            completedAt =
              when {
                input.progress == 100 -> existing?.completedAt ?: now
                else -> null
              },
            archivedAt = existing?.archivedAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
          )
        if (existing == null) planDao.insertItem(item) else planDao.updateItem(item)
        val beforeState =
          existing?.let {
            itemMutationState(if (destinationChanged) movingHierarchy else listOf(existing))
          }
        val afterState = itemMutationState(companions + item)
        val mutationId =
          insertMutation(
            boardId = item.boardId,
            mutationType =
              if (existing == null) PlanMutationType.ITEM_CREATE else PlanMutationType.ITEM_EDIT,
            targetType =
              if (afterState is PlanItemGroupState) PlanMutationTarget.ITEM_GROUP
              else PlanMutationTarget.ITEM,
            summary = if (existing == null) "Created ${item.title}" else "Updated ${item.title}",
            before = beforeState,
            after = afterState,
            now = now,
          )
        JournaledPlanResult(item, mutationId)
      }
    }

  suspend fun deleteItem(id: String): PlanItem? = deleteItemWithUndo(id).value

  /**
   * Removes a leaf task reversibly. The row is archived instead of deleted, so duration blocks
   * remain available for exact Undo and Room never cascades data behind a generic success message.
   */
  suspend fun deleteItemWithUndo(id: String): JournaledPlanResult<PlanItem?> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val item = planDao.getItem(id)
          ?.takeIf { it.archivedAt == null }
          ?: return@withTransaction JournaledPlanResult(null, null)
        require(planDao.countActiveChildren(item.id) == 0) {
          "Remove or re-parent this task's subtasks first"
        }
        require(planDao.getDependenciesFor(item.id).isEmpty()) {
          "Remove this task's dependencies first"
        }
        val now = System.currentTimeMillis()
        val archived = item.copy(archivedAt = now, updatedAt = now)
        planDao.updateItem(archived)
        val mutationId =
          insertMutation(
            boardId = item.boardId,
            mutationType = PlanMutationType.ITEM_DELETE,
            targetType = PlanMutationTarget.ITEM,
            summary = "Removed ${item.title}",
            before = item.toMutationState(),
            after = archived.toMutationState(),
            now = now,
          )
        JournaledPlanResult(item, mutationId)
      }
    }

  /**
   * Moves the complete hierarchy component while patching only destination and rank fields.
   * Cards may be backed by an older UI snapshot, so movement must load fresh rows in the
   * transaction instead of resaving every editable property from that snapshot.
   */
  suspend fun moveItemToColumn(itemId: String, targetColumnId: String?): PlanMoveResult =
    moveItemToColumnWithUndo(itemId, targetColumnId).value

  suspend fun moveItemToColumnWithUndo(
    itemId: String,
    targetColumnId: String?,
  ): JournaledPlanResult<PlanMoveResult> {
    val result = moveItemsToColumnAtomicallyWithUndo(listOf(itemId), targetColumnId)
    val movedItem = result.value.items.first { it.id == itemId }
    return JournaledPlanResult(
      value = PlanMoveResult(movedItem = movedItem, movedCount = result.value.movedCount),
      mutationId = result.mutationId,
    )
  }

  /**
   * Moves every selected hierarchy as one Room transaction and one exact placement journal entry.
   * All fresh-row, board, destination, hierarchy, cycle, and rank checks complete before the first
   * update. A failed update or journal insert rolls the complete transaction back.
   */
  suspend fun moveItemsToColumnAtomicallyWithUndo(
    itemIds: Collection<String>,
    targetColumnId: String?,
    expectedBoardId: String? = null,
  ): JournaledPlanResult<PlanAtomicMoveResult> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val requested = itemIds.filter(String::isNotBlank).toCollection(linkedSetOf())
        require(requested.isNotEmpty()) { "Choose at least one task" }
        val selected =
          requested.map { id ->
            requireNotNull(planDao.getItem(id)) { "A selected task is unavailable" }
              .also { require(it.archivedAt == null) { "A selected task is unavailable" } }
          }
        val boardId = selected.first().boardId
        require(expectedBoardId == null || expectedBoardId == boardId) {
          "The selected tasks changed Plans"
        }
        require(selected.all { it.boardId == boardId }) { "A move cannot cross Plans" }
        val board = requireNotNull(planDao.getBoard(boardId)) { "That Plan is unavailable" }
        require(board.archivedAt == null) { "That Plan is unavailable" }
        val target = targetColumnId?.let { planDao.getColumn(it) }
        require(
          targetColumnId == null ||
            (target != null && target.boardId == boardId && target.archivedAt == null)
        ) {
          "That workflow column does not belong to this board"
        }
        val activeItems = planDao.getAllItems(boardId).filter { it.archivedAt == null }
        val components = atomicMoveComponents(activeItems, requested)
        val selectedByComponent =
          components.associateWith { component ->
            component.mapTo(linkedSetOf(), PlanItem::id).intersect(requested)
          }
        val alreadyAtDestination =
          components
            .filter { component -> component.all { it.columnId == target?.id } }
            .flatMapTo(linkedSetOf()) { component -> selectedByComponent.getValue(component) }
        val componentsToMove =
          components.filterNot { component -> component.all { it.columnId == target?.id } }
        val hierarchyItems = components.flatten()
        if (componentsToMove.isEmpty()) {
          return@withTransaction JournaledPlanResult(
            value =
              PlanAtomicMoveResult(
                requestedItemIds = requested,
                items = hierarchyItems,
                movedItemIds = emptySet(),
                alreadyAtDestinationItemIds = alreadyAtDestination,
              ),
            mutationId = null,
          )
        }

        val affected = componentsToMove.flatten()
        val startRank = planDao.maxItemRankInColumn(boardId, target?.id)
        val now = System.currentTimeMillis()
        val moved =
          affected.mapIndexed { index, current ->
            val rankOffset = Math.multiplyExact(RANK_GAP, index + 1L)
            current.copy(
              columnId = target?.id,
              rank = Math.addExact(startRank, rankOffset),
              updatedAt = now,
            )
          }
        val beforeState =
          PlanMoveState(
            affected
              .map { current ->
                PlanPlacementState(current.id, current.columnId, current.rank, current.updatedAt)
              }
              .sortedBy(PlanPlacementState::itemId)
          )
        val afterState =
          PlanMoveState(
            moved
              .map { current ->
                PlanPlacementState(current.id, current.columnId, current.rank, current.updatedAt)
              }
              .sortedBy(PlanPlacementState::itemId)
          )
        planDao.updateItems(moved)
        val mutationId =
          insertMutation(
            boardId = boardId,
            mutationType = PlanMutationType.ITEM_MOVE,
            targetType =
              if (moved.size == 1) PlanMutationTarget.ITEM else PlanMutationTarget.ITEM_GROUP,
            summary =
              if (requested.size == 1) "Moved ${selected.single().title}"
              else "Moved ${requested.size} selected tasks atomically",
            before = beforeState,
            after = afterState,
            now = now,
          )
        val movedById = moved.associateBy(PlanItem::id)
        JournaledPlanResult(
          value =
            PlanAtomicMoveResult(
              requestedItemIds = requested,
              items = hierarchyItems.map { movedById[it.id] ?: it },
              movedItemIds = movedById.keys,
              alreadyAtDestinationItemIds = alreadyAtDestination,
            ),
          mutationId = mutationId,
        )
      }
    }

  /** Progress is another focused mutation so a checkbox cannot clobber newer task metadata. */
  suspend fun setItemProgress(itemId: String, progress: Int): PlanItem =
    setItemProgressWithUndo(itemId, progress).value

  suspend fun setItemProgressWithUndo(
    itemId: String,
    progress: Int,
  ): JournaledPlanResult<PlanItem> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        require(progress in 0..100) { "Progress must be between 0 and 100" }
        val item = requireNotNull(planDao.getItem(itemId)) { "That task is unavailable" }
        require(item.archivedAt == null) { "That task is unavailable" }
        if (item.progress == progress) return@withTransaction JournaledPlanResult(item, null)
        val now = System.currentTimeMillis()
        val updated =
          item
          .copy(
            progress = progress,
            completedAt = if (progress == 100) item.completedAt ?: now else null,
            updatedAt = now,
          )
        planDao.updateItem(updated)
        val mutationId =
          insertMutation(
            boardId = item.boardId,
            mutationType = PlanMutationType.ITEM_PROGRESS,
            targetType = PlanMutationTarget.ITEM,
            summary = "Changed progress for ${item.title}",
            before =
              PlanProgressState(item.id, item.progress, item.completedAt, item.updatedAt),
            after =
              PlanProgressState(
                updated.id,
                updated.progress,
                updated.completedAt,
                updated.updatedAt,
              ),
            now = now,
          )
        JournaledPlanResult(updated, mutationId)
      }
    }

  /**
   * Assigns one reusable working schedule, or removes the row to inherit the active default.
   * This is deliberately a focused command: saving unrelated task fields cannot partially commit
   * or accidentally roll back a schedule choice made from the editor.
   */
  suspend fun assignItemWorkingScheduleWithUndo(
    itemId: String,
    workScheduleId: String?,
  ): JournaledPlanResult<PlanItemSchedule?> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val item = requireNotNull(planDao.getItem(itemId)) { "That task is unavailable" }
        require(item.archivedAt == null) { "That task is unavailable" }
        // Validate the exact resulting meaning before changing the mapping. Null is the only path
        // that may resolve through the default schedule.
        val selectedCalendar = loadWorkingCalendar(workScheduleId)
        val existing = planDao.getPlanItemSchedule(item.id)
        if (existing?.workScheduleId == workScheduleId || (existing == null && workScheduleId == null)) {
          return@withTransaction JournaledPlanResult(existing, null)
        }
        val now = System.currentTimeMillis()
        val replacement =
          workScheduleId?.let {
            PlanItemSchedule(
              planItemId = item.id,
              workScheduleId = selectedCalendar.schedule.id,
              createdAt = existing?.createdAt ?: now,
              updatedAt = now,
            )
          }
        if (existing != null) planDao.deletePlanItemSchedule(existing)
        if (replacement != null) planDao.insertPlanItemSchedule(replacement)
        val mutationId =
          insertMutation(
            boardId = item.boardId,
            mutationType = PlanMutationType.ITEM_SCHEDULE_ASSIGN,
            targetType = PlanMutationTarget.ITEM_SCHEDULE,
            summary =
              replacement?.let { "Assigned ${selectedCalendar.schedule.name} to ${item.title}" }
                ?: "Set ${item.title} to inherit the default schedule",
            before = existing?.toMutationState() ?: PlanItemScheduleState.inherited(item.id),
            after = replacement?.toMutationState() ?: PlanItemScheduleState.inherited(item.id),
            now = now,
          )
        JournaledPlanResult(replacement, mutationId)
      }
    }

  suspend fun saveBlock(input: PlanBlockInput): PlanBlock =
    saveBlockWithUndo(input).value

  suspend fun saveBlockWithUndo(input: PlanBlockInput): JournaledPlanResult<PlanBlock> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val written = validateAndWriteBlock(input, System.currentTimeMillis())
        val item = requireNotNull(planDao.getItem(written.block.planItemId))
        val mutationId =
          insertMutation(
            boardId = item.boardId,
            mutationType =
              if (written.before == null) PlanMutationType.BLOCK_CREATE
              else PlanMutationType.BLOCK_EDIT,
            targetType = PlanMutationTarget.BLOCK,
            summary =
              if (written.before == null) "Scheduled ${item.title}"
              else "Updated schedule for ${item.title}",
            before = written.before,
            after = written.block.toMutationState(),
            now = written.block.updatedAt,
          )
        JournaledPlanResult(written.block, mutationId)
      }
    }

  /** A written block and what it replaced, so the caller can journal without re-reading. */
  private data class BlockWrite(val block: PlanBlock, val before: PlanBlockState?)

  /** The block rules, in one place: a batch cannot get a weaker check than a single save. */
  private suspend fun validateAndWriteBlock(input: PlanBlockInput, now: Long): BlockWrite =
      run {
        require(input.endAt > input.startAt) { "A plan block must end after it starts" }
        require(input.position == null || input.position >= 0) { "Block position cannot be negative" }
        val item = planDao.getItem(input.planItemId)
        require(item != null && item.archivedAt == null) { "That task is unavailable" }
        require(!item.isMilestone) {
          "Milestones are points in time and cannot carry duration blocks"
        }
        val existing = input.id?.let { planDao.getBlock(it) }
        require(input.id == null || existing != null) { "That plan block is unavailable" }
        require(existing == null || existing.planItemId == item.id) {
          "A plan block cannot move between tasks"
        }
        require(
          existing == null ||
            !existing.locked ||
            (existing.startAt == input.startAt && existing.endAt == input.endAt)
        ) {
          "Unlock this plan block before moving or resizing it"
        }
        val workingCalendar = loadWorkingCalendarForItem(item.id)
        val board = requireNotNull(planDao.getBoard(item.boardId)) { "That Plan is unavailable" }
        // Archived tasks deliberately keep their blocks during the Undo window. They are not part
        // of the live scheduling graph and must not poison an unrelated block preview as orphans.
        val activeItems = planDao.getAllItems(item.boardId).filter { it.archivedAt == null }
        val activeItemIds = activeItems.mapTo(mutableSetOf()) { it.id }
        val activeBlocks =
          planDao.getBlocksForBoard(item.boardId).filter { it.planItemId in activeItemIds }
        val bufferMs = workingCalendar.spec.bufferMinutes.toLong() * 60_000L
        val eventRangeStart =
          runCatching { Math.subtractExact(input.startAt, bufferMs) }.getOrDefault(Long.MIN_VALUE)
        val eventRangeEnd =
          runCatching { Math.addExact(input.endAt, bufferMs) }.getOrDefault(Long.MAX_VALUE)
        val preview =
          PlanBlockPreview.evaluate(
            item = item,
            blockId = existing?.id,
            proposedStart = input.startAt,
            proposedEnd = input.endAt,
            items = activeItems,
            blocks = activeBlocks,
            fixedCommitments =
              database.eventDao().getEventsInRangeSync(eventRangeStart, eventRangeEnd)
                .filter { it.kanbanBoard == board.name }
                .map { event -> WorkingInterval(event.startTime, event.endTime) },
            dependencies = planDao.getDependenciesForBoard(item.boardId),
            workSchedule = workingCalendar.spec,
          )
        require(preview.canCommit) {
          preview.issues.filter { it.blocking }.joinToString(" ") { it.explanation }
            .ifBlank { "That Plan block is not safe to schedule" }
        }
        val block =
          PlanBlock(
            id = input.id ?: "block_${UUID.randomUUID()}",
            planItemId = item.id,
            startAt = input.startAt,
            endAt = input.endAt,
            position =
              input.position
                ?: existing?.position
                ?: (planDao.maxBlockPosition(item.id) + 1),
            locked = input.locked,
            linkedEventId = input.linkedEventId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
          )
        if (existing == null) planDao.insertBlock(block) else planDao.updateBlock(block)
        BlockWrite(block, existing?.toMutationState())
      }

  /**
   * Writes a whole auto-plan as one command.
   *
   * Every proposal is revalidated here against the same rules a hand-made block obeys — the task's
   * own working schedule, fixed commitments, locks, dependencies — because a proposal computed a
   * moment ago is a suggestion, not a permit. If any one of them is refused, nothing is written and
   * the reason names the task, and the whole batch is a single History entry so Undo puts the week
   * back exactly as it was.
   */
  suspend fun applyPlanProposals(
    proposals: List<PlanProposal>,
    expectedBoardId: String,
  ): JournaledPlanResult<List<PlanBlock>> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
          require(proposals.isNotEmpty()) { "There is nothing to apply" }
          val board = requireNotNull(planDao.getBoard(expectedBoardId)) { "That Plan is unavailable" }
          require(board.archivedAt == null) { "That Plan is unavailable" }
          val now = System.currentTimeMillis()
          val created = mutableListOf<PlanBlock>()

          proposals.sortedBy(PlanProposal::startAt).forEach { proposal ->
            val item =
              requireNotNull(planDao.getItem(proposal.itemId)) { "A proposed task is unavailable" }
            require(item.boardId == expectedBoardId) { "A proposal crossed Plans" }
            require(item.archivedAt == null && !item.locked) {
              "\"${'$'}{item.title}\" is locked or gone, so its proposal was not applied"
            }
            val saved =
              validateAndWriteBlock(
                PlanBlockInput(
                  planItemId = proposal.itemId,
                  startAt = proposal.startAt,
                  endAt = proposal.endAt,
                ),
                now = now,
              )
            created += saved.block
          }

          val mutationId =
            insertMutation(
              boardId = expectedBoardId,
              mutationType = PlanMutationType.BLOCK_CREATE,
              targetType =
                if (created.size == 1) PlanMutationTarget.BLOCK else PlanMutationTarget.BLOCK_GROUP,
              summary =
                "Applied a plan for ${'$'}{created.map { it.planItemId }.distinct().size} " +
                  if (created.map { it.planItemId }.distinct().size == 1) "task" else "tasks",
              before = null,
              after =
                if (created.size == 1) created.single().toMutationState()
                else PlanBlockGroupState(created.map { it.toMutationState() }),
              now = now,
            )
          JournaledPlanResult(created.toList(), mutationId)
      }
    }

  suspend fun deleteBlock(id: String): PlanBlock? = deleteBlockWithUndo(id).value

  suspend fun deleteBlockWithUndo(id: String): JournaledPlanResult<PlanBlock?> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val block = planDao.getBlock(id)
          ?: return@withTransaction JournaledPlanResult(null, null)
        require(!block.locked) { "Unlock this plan block before removing it" }
        val item = requireNotNull(planDao.getItem(block.planItemId)) { "That task is unavailable" }
        val now = System.currentTimeMillis()
        planDao.deleteBlock(block)
        val mutationId =
          insertMutation(
            boardId = item.boardId,
            mutationType = PlanMutationType.BLOCK_DELETE,
            targetType = PlanMutationTarget.BLOCK,
            summary = "Removed schedule for ${item.title}",
            before = block.toMutationState(),
            after = null,
            now = now,
          )
        JournaledPlanResult(block, mutationId)
      }
    }

  suspend fun addDependency(
    predecessorId: String,
    successorId: String,
    type: String = PlanDependencyType.FINISH_TO_START,
    lagMinutes: Int = 0,
  ): PlanDependency =
    addDependencyWithUndo(predecessorId, successorId, type, lagMinutes).value

  suspend fun addDependencyWithUndo(
    predecessorId: String,
    successorId: String,
    type: String = PlanDependencyType.FINISH_TO_START,
    lagMinutes: Int = 0,
  ): JournaledPlanResult<PlanDependency> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        require(predecessorId != successorId) { "A task cannot depend on itself" }
        require(type in PlanDependencyType.All) { "Unknown dependency type" }
        val predecessor = planDao.getItem(predecessorId)
        val successor = planDao.getItem(successorId)
        require(predecessor != null && successor != null) { "Both dependency tasks must exist" }
        require(predecessor.boardId == successor.boardId) {
          "Dependencies must stay within one board"
        }
        val existing = planDao.getDependenciesForBoard(predecessor.boardId)
        require(
          existing.none {
            it.predecessorId == predecessorId && it.successorId == successorId
          }
        ) {
          "That dependency already exists"
        }
        require(!hasPath(existing, from = successorId, to = predecessorId)) {
          "That dependency would create a cycle"
        }
        val now = System.currentTimeMillis()
        val dependency =
          PlanDependency(
            id = "dependency_${UUID.randomUUID()}",
            boardId = predecessor.boardId,
            predecessorId = predecessorId,
            successorId = successorId,
            type = type,
            lagMinutes = lagMinutes,
            createdAt = now,
            updatedAt = now,
          )
        planDao.insertDependency(dependency)
        val mutationId =
          insertMutation(
            boardId = dependency.boardId,
            mutationType = PlanMutationType.DEPENDENCY_CREATE,
            targetType = PlanMutationTarget.DEPENDENCY,
            summary = "Added task dependency",
            before = null,
            after = dependency.toMutationState(),
            now = now,
          )
        JournaledPlanResult(dependency, mutationId)
      }
    }

  suspend fun updateDependency(
    id: String,
    type: String,
    lagMinutes: Int,
  ): JournaledPlanResult<PlanDependency> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        require(type in PlanDependencyType.All) { "Unknown dependency type" }
        val existing = requireNotNull(planDao.getDependency(id)) { "That dependency is unavailable" }
        if (existing.type == type && existing.lagMinutes == lagMinutes) {
          return@withTransaction JournaledPlanResult(existing, null)
        }
        val now = System.currentTimeMillis()
        val updated = existing.copy(type = type, lagMinutes = lagMinutes, updatedAt = now)
        planDao.updateDependency(updated)
        val mutationId =
          insertMutation(
            boardId = existing.boardId,
            mutationType = PlanMutationType.DEPENDENCY_EDIT,
            targetType = PlanMutationTarget.DEPENDENCY,
            summary = "Updated task dependency",
            before = existing.toMutationState(),
            after = updated.toMutationState(),
            now = now,
          )
        JournaledPlanResult(updated, mutationId)
      }
    }

  suspend fun deleteDependency(id: String): JournaledPlanResult<PlanDependency?> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val dependency = planDao.getDependency(id)
          ?: return@withTransaction JournaledPlanResult(null, null)
        val now = System.currentTimeMillis()
        planDao.deleteDependency(dependency)
        val mutationId =
          insertMutation(
            boardId = dependency.boardId,
            mutationType = PlanMutationType.DEPENDENCY_DELETE,
            targetType = PlanMutationTarget.DEPENDENCY,
            summary = "Removed task dependency",
            before = dependency.toMutationState(),
            after = null,
            now = now,
          )
        JournaledPlanResult(dependency, mutationId)
      }
    }

  fun observeMutationHistory(boardId: String?, limit: Int = 30): Flow<List<PlanMutation>> {
    require(limit in 1..200) { "History limit must be between 1 and 200" }
    return planDao.observePlanMutations(boardId, limit)
  }

  suspend fun undoLatest(boardId: String?): PlanUndoResult =
    withContext(Dispatchers.IO) {
      val now = System.currentTimeMillis()
      val latest = planDao.getLatestUndoableMutation(boardId, now)
        ?: return@withContext PlanUndoResult(PlanUndoStatus.UNAVAILABLE)
      undoMutation(latest.id)
    }

  /**
   * Restores exactly one journaled command. Live state is compared with the stored after snapshot
   * before the one-way status transition, so an older Undo can never overwrite newer work.
   */
  suspend fun undoMutation(id: String): PlanUndoResult =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val mutation =
          planDao.getPlanMutation(id)
            ?: return@withTransaction PlanUndoResult(PlanUndoStatus.UNAVAILABLE)
        val now = System.currentTimeMillis()
        if (mutation.status != PlanMutationStatus.APPLIED) {
          return@withTransaction PlanUndoResult(
            if (mutation.status == PlanMutationStatus.EXPIRED) PlanUndoStatus.EXPIRED
            else PlanUndoStatus.UNAVAILABLE,
            mutation.summary,
          )
        }
        // Bound to a local: PlanMutation lives in :planning-core now, and Kotlin will not
        // smart-cast a val it does not own.
        val expiresAt = mutation.expiresAt
        if (expiresAt != null && expiresAt <= now) {
          planDao.expirePlanMutations(now)
          return@withTransaction PlanUndoResult(PlanUndoStatus.EXPIRED, mutation.summary)
        }

        val before = decodeMutationState(mutation, mutation.beforeJson)
        val after = decodeMutationState(mutation, mutation.afterJson)
        if (
          (mutation.beforeJson != null && before == null) ||
            (mutation.afterJson != null && after == null) ||
            !validNullSides(mutation.mutationType, before, after)
        ) {
          return@withTransaction PlanUndoResult(PlanUndoStatus.UNSUPPORTED, mutation.summary)
        }
        if (!matchesMutationState(mutation, after)) {
          return@withTransaction PlanUndoResult(PlanUndoStatus.STALE, mutation.summary)
        }
        if (!canApplyInverse(mutation, before)) {
          return@withTransaction PlanUndoResult(PlanUndoStatus.STALE, mutation.summary)
        }
        if (planDao.markPlanMutationUndone(mutation.id, now) != 1) {
          return@withTransaction PlanUndoResult(PlanUndoStatus.UNAVAILABLE, mutation.summary)
        }
        applyInverse(mutation, before)
        PlanUndoResult(PlanUndoStatus.UNDONE, mutation.summary)
      }
    }

  /**
   * The configured recovery window, read at write time so a change made after the setting moved
   * uses the new value. A missing or malformed setting falls back to the policy default.
   */
  internal suspend fun undoWindowMs(): Long =
    UndoWindowPolicy.windowMs(settingDao.getSettingSync(SettingKeys.UNDO_WINDOW_SECONDS)?.value)

  private suspend fun insertMutation(
    boardId: String?,
    mutationType: String,
    targetType: String,
    summary: String,
    before: PlanMutationState?,
    after: PlanMutationState?,
    now: Long,
  ): String {
    require(mutationType in PlanMutationType.All) { "Unknown mutation type" }
    require(before != null || after != null) { "A mutation needs a before or after state" }
    val encodedBefore = before?.let(PlanMutationCodec::encode)
    val encodedAfter = after?.let(PlanMutationCodec::encode)
    val targetIdsJson = encodedAfter?.targetIdsJson ?: requireNotNull(encodedBefore).targetIdsJson
    require(encodedBefore == null || encodedBefore.targetIdsJson == targetIdsJson) {
      "Before and after mutation targets differ"
    }
    val cleanSummary = summary.trim()
    require(cleanSummary.isNotEmpty() && cleanSummary.length <= 240) { "Invalid mutation summary" }
    val mutationId = "mutation_${UUID.randomUUID()}"
    planDao.insertPlanMutation(
      PlanMutation(
        id = mutationId,
        boardId = boardId,
        mutationType = mutationType,
        targetType = targetType,
        targetIdsJson = targetIdsJson,
        summary = cleanSummary,
        beforeJson = encodedBefore?.stateJson,
        afterJson = encodedAfter?.stateJson,
        status = PlanMutationStatus.APPLIED,
        origin = PlanMutationOrigin.USER,
        schemaVersion = PlanMutationCodec.SCHEMA_VERSION,
        createdAt = now,
        updatedAt = now,
        expiresAt = now + undoWindowMs(),
      )
    )
    return mutationId
  }

  private fun itemMutationState(items: List<PlanItem>): PlanMutationState {
    val snapshots = items.sortedBy(PlanItem::id).map(PlanItem::toMutationState)
    require(snapshots.isNotEmpty()) { "A task mutation needs a task" }
    return if (snapshots.size == 1) snapshots.single() else PlanItemGroupState(snapshots)
  }

  private fun decodeMutationState(
    mutation: PlanMutation,
    payload: String?,
  ): PlanMutationState? =
    if (payload == null) null
    else {
      PlanMutationCodec.decode(
        mutationType = mutation.mutationType,
        targetIdsJson = mutation.targetIdsJson,
        stateJson = payload,
        schemaVersion = mutation.schemaVersion,
      )
    }

  private fun validNullSides(
    mutationType: String,
    before: PlanMutationState?,
    after: PlanMutationState?,
  ): Boolean =
    when (mutationType) {
      PlanMutationType.ITEM_CREATE,
      PlanMutationType.BLOCK_CREATE,
      PlanMutationType.DEPENDENCY_CREATE -> before == null && after != null
      PlanMutationType.BLOCK_DELETE,
      PlanMutationType.DEPENDENCY_DELETE -> before != null && after == null
      else -> before != null && after != null
    }

  private suspend fun matchesMutationState(
    mutation: PlanMutation,
    state: PlanMutationState?,
  ): Boolean =
    when (state) {
      null ->
        when (mutation.targetType) {
          // A batch create has many targets; every one of them has to be gone for the undo to be
          // the exact inverse of what was written.
          PlanMutationTarget.BLOCK_GROUP ->
            mutation.allTargetIds().let { ids ->
              ids.isNotEmpty() && ids.all { planDao.getBlock(it) == null }
            }
          PlanMutationTarget.BLOCK -> mutation.singleTargetId()?.let { planDao.getBlock(it) == null } == true
          PlanMutationTarget.DEPENDENCY ->
            mutation.singleTargetId()?.let { planDao.getDependency(it) == null } == true
          PlanMutationTarget.ITEM -> mutation.singleTargetId()?.let { planDao.getItem(it) == null } == true
          else -> false
        }
      is PlanItemState -> planDao.getItem(state.id) == state.toModel()
      is PlanItemGroupState ->
        state.items.all { item -> planDao.getItem(item.id) == item.toModel() }
      is PlanBlockGroupState ->
        state.blocks.all { block -> planDao.getBlock(block.id) == block.toModel() } &&
          state.absentIds.all { planDao.getBlock(it) == null }
      is PlanMoveState ->
        state.items.all { expected ->
          planDao.getItem(expected.itemId)?.let { actual ->
            actual.columnId == expected.columnId &&
              actual.rank == expected.rank &&
              actual.updatedAt == expected.updatedAt
          } == true
        }
      is PlanProgressState ->
        planDao.getItem(state.itemId)?.let { actual ->
          actual.progress == state.progress &&
            actual.completedAt == state.completedAt &&
            actual.updatedAt == state.updatedAt
        } == true
      is PlanBlockState -> planDao.getBlock(state.id) == state.toModel()
      is PlanDependencyState -> planDao.getDependency(state.id) == state.toModel()
      is PlanItemScheduleState ->
        planDao.getPlanItemSchedule(state.planItemId) == state.toModelOrNull()
      is PlanCatalogState -> matchesCatalogState(state)
      is WorkingScheduleMutationState -> matchesWorkingScheduleState(state)
    }

  private suspend fun canApplyInverse(
    mutation: PlanMutation,
    before: PlanMutationState?,
  ): Boolean {
    if (before is PlanCatalogState) return canRestoreCatalogState(before)
    if (before is WorkingScheduleMutationState) {
      return canRestoreWorkingScheduleState(before)
    }
    if (before is PlanItemScheduleState) {
      val item = planDao.getItem(before.planItemId)
      if (item == null || item.archivedAt != null) return false
      return runCatching { loadWorkingCalendar(before.workScheduleId) }.isSuccess
    }
    if (before != null) return true
    return when (mutation.mutationType) {
      PlanMutationType.ITEM_CREATE -> {
        val id = mutation.singleTargetId() ?: return false
        planDao.getChildren(id).isEmpty() &&
          planDao.getBlocksForItem(id).isEmpty() &&
          planDao.getDependenciesFor(id).isEmpty() &&
          planDao.getPlanItemSchedule(id) == null
      }
      PlanMutationType.BLOCK_CREATE -> mutation.allTargetIds().isNotEmpty()
      PlanMutationType.DEPENDENCY_CREATE -> true
      else -> false
    }
  }

  private suspend fun applyInverse(mutation: PlanMutation, before: PlanMutationState?) {
    if (before == null) {
      when (mutation.mutationType) {
        PlanMutationType.ITEM_CREATE ->
          planDao.getItem(requireNotNull(mutation.singleTargetId()))?.let { planDao.deleteItem(it) }
        PlanMutationType.BLOCK_CREATE -> {
          val ids = mutation.allTargetIds()
          require(ids.isNotEmpty()) { "A create mutation names no target" }
          ids.forEach { id -> planDao.getBlock(id)?.let { planDao.deleteBlock(it) } }
        }
        PlanMutationType.DEPENDENCY_CREATE ->
          planDao.getDependency(requireNotNull(mutation.singleTargetId()))?.let {
            planDao.deleteDependency(it)
          }
        else -> error("Unsupported inverse")
      }
      return
    }
    when (before) {
      is PlanItemState -> {
        if (planDao.getItem(before.id) == null) planDao.insertItem(before.toModel())
        else planDao.updateItem(before.toModel())
      }
      is PlanItemGroupState -> planDao.updateItems(before.items.map(PlanItemState::toModel))
      is PlanBlockGroupState -> {
        before.blocks.forEach { snapshot ->
          val model = snapshot.toModel()
          if (planDao.getBlock(snapshot.id) == null) planDao.insertBlock(model)
          else planDao.updateBlock(model)
        }
        // A block the earlier state did not have must go, or undoing a replacement leaves both.
        before.absentIds.forEach { id -> planDao.getBlock(id)?.let { planDao.deleteBlock(it) } }
      }
      is PlanMoveState -> {
        val restored =
          before.items.map { snapshot ->
            requireNotNull(planDao.getItem(snapshot.itemId)).copy(
              columnId = snapshot.columnId,
              rank = snapshot.rank,
              updatedAt = snapshot.updatedAt,
            )
          }
        planDao.updateItems(restored)
      }
      is PlanProgressState -> {
        val current = requireNotNull(planDao.getItem(before.itemId))
        planDao.updateItem(
          current.copy(
            progress = before.progress,
            completedAt = before.completedAt,
            updatedAt = before.updatedAt,
          )
        )
      }
      is PlanBlockState -> {
        if (planDao.getBlock(before.id) == null) planDao.insertBlock(before.toModel())
        else planDao.updateBlock(before.toModel())
      }
      is PlanDependencyState -> {
        if (planDao.getDependency(before.id) == null) planDao.insertDependency(before.toModel())
        else planDao.updateDependency(before.toModel())
      }
      is PlanItemScheduleState -> {
        planDao.getPlanItemSchedule(before.planItemId)?.let { current ->
          planDao.deletePlanItemSchedule(current)
        }
        before.toModelOrNull()?.let { restored -> planDao.insertPlanItemSchedule(restored) }
      }
      is PlanCatalogState -> restoreCatalogState(before)
      is WorkingScheduleMutationState -> restoreWorkingScheduleState(before)
    }
  }

  private suspend fun updateWorkingScheduleInTransaction(
    existing: WorkSchedule,
    name: String,
    spec: WorkingCalendarSpec,
    now: Long,
  ): JournaledPlanResult<PersistedWorkingCalendar> {
    require(existing.archivedAt == null) { "That working schedule is unavailable" }
    val (cleanName, nameKey) = canonicalWorkingScheduleName(name)
    val owner = planDao.getWorkScheduleByNameKey(nameKey)
    require(owner == null || owner.id == existing.id) {
      "A working schedule with that name already exists"
    }
    val current =
      WorkingCalendarMapper.fromEntities(
        existing,
        planDao.getWorkScheduleWindows(existing.id),
      )
    val replacement =
      WorkingCalendarMapper.forUpdate(
        schedule = existing.copy(name = cleanName, nameKey = nameKey),
        spec = spec,
        updatedAt = now,
      )
    if (current.schedule.name == cleanName && current.spec == replacement.spec) {
      return JournaledPlanResult(current, null)
    }

    val before = captureWorkingScheduleState(existing.id, setOf(existing.id))
    planDao.updateWorkSchedule(replacement.schedule)
    planDao.deleteWorkScheduleWindows(replacement.schedule.id)
    if (replacement.windows.isNotEmpty()) {
      planDao.insertWorkScheduleWindows(replacement.windows)
    }
    requireWorkingScheduleCatalogInvariant()
    val after = captureWorkingScheduleState(existing.id, setOf(existing.id))
    val mutationId =
      insertMutation(
        boardId = null,
        mutationType = PlanMutationType.WORK_SCHEDULE_UPDATE,
        targetType = PlanMutationTarget.WORK_SCHEDULE,
        summary = "Updated working schedule $cleanName",
        before = before,
        after = after,
        now = now,
      )
    return JournaledPlanResult(replacement, mutationId)
  }

  private suspend fun captureWorkingScheduleState(
    scopeId: String,
    scheduleIds: Set<String>,
    assignmentScheduleIds: Set<String> = emptySet(),
  ): WorkingScheduleMutationState {
    require(scopeId in scheduleIds) { "Working-schedule scope must be tracked" }
    require(assignmentScheduleIds.all { it in scheduleIds }) {
      "Assignment scope must belong to a tracked working schedule"
    }
    val orderedIds = scheduleIds.toSortedSet()
    val schedules = orderedIds.associateWith { id -> planDao.getWorkSchedule(id) }
    val windows =
      orderedIds.associateWith { id ->
        if (schedules.getValue(id) == null) emptyList()
        else planDao.getWorkScheduleWindows(id).sortedBy(WorkScheduleWindow::id)
      }
    val assignments =
      assignmentScheduleIds.toSortedSet().associateWith { id ->
        planDao.getPlanItemSchedulesForWorkSchedule(id).sortedBy(PlanItemSchedule::planItemId)
      }
    return WorkingScheduleMutationState(scopeId, schedules, windows, assignments)
  }

  private suspend fun requireWorkingScheduleCatalogInvariant(): WorkSchedule {
    val schedules = planDao.getAllWorkSchedules()
    val defaults = schedules.filter { it.isDefault }
    require(defaults.size == 1 && defaults.single().archivedAt == null) {
      "Exactly one active default working schedule is required"
    }
    val default = defaults.single()
    WorkingCalendarMapper.fromEntities(default, planDao.getWorkScheduleWindows(default.id))
    return default
  }

  private fun canonicalWorkingScheduleName(name: String): Pair<String, String> {
    val cleanName = name.trim()
    require(
      cleanName.isNotEmpty() &&
        cleanName.length <= MAX_WORK_SCHEDULE_NAME_LENGTH &&
        cleanName.none(Char::isISOControl)
    ) {
      "A working schedule needs a name of 80 characters or fewer"
    }
    return cleanName to LegacyPlanCatalogBuilder.nameKey(cleanName)
  }

  private suspend fun matchesWorkingScheduleState(
    state: WorkingScheduleMutationState,
  ): Boolean {
    if (state.schedules.any { (id, expected) -> planDao.getWorkSchedule(id) != expected }) {
      return false
    }
    if (
      state.windows.any { (id, expected) ->
        planDao.getWorkScheduleWindows(id).sortedBy(WorkScheduleWindow::id) !=
          expected.sortedBy(WorkScheduleWindow::id)
      }
    ) {
      return false
    }
    return state.assignmentScopes.all { (id, expected) ->
      planDao.getPlanItemSchedulesForWorkSchedule(id).sortedBy(PlanItemSchedule::planItemId) ==
        expected.sortedBy(PlanItemSchedule::planItemId)
    }
  }

  private suspend fun canRestoreWorkingScheduleState(
    state: WorkingScheduleMutationState,
  ): Boolean {
    val resulting = planDao.getAllWorkSchedules().associateBy { it.id }.toMutableMap()
    state.schedules.forEach { (id, schedule) ->
      if (schedule == null) resulting.remove(id) else resulting[id] = schedule
    }
    val defaults = resulting.values.filter { it.isDefault }
    if (defaults.size != 1 || defaults.single().archivedAt != null) return false
    if (resulting.values.map { it.nameKey }.distinct().size != resulting.size) return false

    state.schedules.forEach { (id, schedule) ->
      if (schedule == null) {
        if (planDao.getPlanItemSchedulesForWorkSchedule(id).isNotEmpty()) return false
      } else {
        val valid =
          runCatching {
              WorkingCalendarMapper.fromEntities(schedule, state.windows.getValue(id))
            }
            .isSuccess
        if (!valid) return false
      }
    }
    state.assignmentScopes.values.flatten().forEach { assignment ->
      if (planDao.getItem(assignment.planItemId) == null) return false
      val target = resulting[assignment.workScheduleId]
      if (target == null || target.archivedAt != null) return false
    }
    return true
  }

  private suspend fun restoreWorkingScheduleState(state: WorkingScheduleMutationState) {
    state.assignmentScopes.keys.forEach { scheduleId ->
      planDao.getPlanItemSchedulesForWorkSchedule(scheduleId).forEach { current ->
        planDao.deletePlanItemSchedule(current)
      }
    }
    state.schedules.keys.forEach { scheduleId ->
      planDao.deleteWorkScheduleWindows(scheduleId)
    }
    state.schedules.forEach { (id, schedule) ->
      val current = planDao.getWorkSchedule(id)
      when {
        schedule == null && current != null -> planDao.deleteWorkSchedule(current)
        schedule != null && current == null -> planDao.insertWorkSchedule(schedule)
        schedule != null -> planDao.updateWorkSchedule(schedule)
      }
    }
    state.windows.forEach { (scheduleId, windows) ->
      if (state.schedules.getValue(scheduleId) != null && windows.isNotEmpty()) {
        planDao.insertWorkScheduleWindows(windows)
      }
    }
    state.assignmentScopes.values.flatten().sortedBy(PlanItemSchedule::planItemId).forEach {
      assignment ->
      planDao.insertPlanItemSchedule(assignment)
    }
    requireWorkingScheduleCatalogInvariant()
  }

  private suspend fun matchesCatalogState(state: PlanCatalogState): Boolean {
    if (state.boards.any { (id, expected) -> planDao.getBoard(id) != expected }) return false
    if (state.columns.any { (id, expected) -> planDao.getColumn(id) != expected }) return false
    if (state.items.any { (id, expected) -> planDao.getItem(id) != expected }) return false
    if (state.events.any { (id, expected) -> eventDao.getEventById(id) != expected }) return false
    if (
      state.settings.any { (key, expected) ->
        settingDao.getSettingSync(key)?.value != expected
      }
    ) {
      return false
    }
    return true
  }

  /**
   * Checks constraints and newly-created dependants before marking the journal row undone. A
   * catalog command can therefore become stale, but can never discover a uniqueness/FK conflict
   * halfway through its inverse and leave the user guessing what was restored.
   */
  private suspend fun canRestoreCatalogState(state: PlanCatalogState): Boolean {
    state.boards.forEach { (id, before) ->
      if (before == null) {
        val current = planDao.getBoard(id) ?: return@forEach
        if (
          planDao.getAllColumns(id).any { it.id !in state.columns } ||
            planDao.getAllItems(id).any { it.id !in state.items } ||
            eventDao.getEventsForBoardSync(current.name).any { it.id !in state.events } ||
            planDao.countSavedViewsForBoard(id) > 0 ||
            planDao.countPlanMutationsForBoard(id) > 0
        ) {
          return false
        }
        if (current.isDefault) return false
      } else {
        val owner = planDao.getBoardByNameKey(before.nameKey)
        if (owner != null && owner.id != id && owner.id !in state.boards) return false
      }
    }
    state.columns.forEach { (id, before) ->
      if (before == null) {
        if (planDao.getColumn(id) != null && planDao.countItemsForColumn(id) > 0) return false
      } else {
        val owner = planDao.getColumnByNameKey(before.boardId, before.nameKey)
        if (owner != null && owner.id != id && owner.id !in state.columns) return false
        val boardWillExist =
          state.boards[before.boardId]?.let { true } ?: (planDao.getBoard(before.boardId) != null)
        if (!boardWillExist) return false
      }
    }
    state.items.values.filterNotNull().forEach { item ->
      val boardWillExist =
        state.boards[item.boardId]?.let { true } ?: (planDao.getBoard(item.boardId) != null)
      if (!boardWillExist) return false
      val columnId = item.columnId
      if (columnId != null) {
        val columnWillExist =
          state.columns[columnId]?.let { true } ?: (planDao.getColumn(columnId) != null)
        if (!columnWillExist) return false
      }
      val parentId = item.parentId
      if (parentId != null) {
        val parentWillExist =
          state.items[parentId]?.let { true } ?: (planDao.getItem(parentId) != null)
        if (!parentWillExist) return false
      }
    }
    return true
  }

  private suspend fun restoreCatalogState(state: PlanCatalogState) {
    // Remove command-created dependants before their parents.
    state.items.filterValues { it == null }.keys.forEach { id ->
      planDao.getItem(id)?.let { planDao.deleteItem(it) }
    }
    state.columns.filterValues { it == null }.keys.forEach { id ->
      planDao.getColumn(id)?.let { planDao.deleteColumn(it) }
    }
    state.boards.filterValues { it == null }.keys.forEach { id ->
      planDao.getBoard(id)?.let { planDao.deleteBoard(it) }
    }

    // Rebuild parents before children, then restore exact task rows.
    state.boards.values.filterNotNull().forEach { board ->
      if (planDao.getBoard(board.id) == null) planDao.insertBoard(board)
      else planDao.updateBoard(board)
    }
    state.columns.values.filterNotNull().forEach { column ->
      if (planDao.getColumn(column.id) == null) planDao.insertColumn(column)
      else planDao.updateColumn(column)
    }
    state.items.values.filterNotNull().forEach { item ->
      if (planDao.getItem(item.id) == null) planDao.insertItem(item)
      else planDao.updateItem(item)
    }

    state.events.forEach { (id, event) ->
      if (event == null) eventDao.deleteEventById(id)
      else eventDao.insertEvents(listOf(event))
    }
    state.settings.forEach { (key, value) ->
      if (value == null) settingDao.deleteSetting(key)
      else settingDao.insertSetting(SystemSetting(key, value))
    }
  }

  private fun PlanMutation.singleTargetId(): String? =
    runCatching { SavedViewCodec.decodeStringArray(targetIdsJson).singleOrNull() }.getOrNull()

  private fun PlanMutation.allTargetIds(): List<String> =
    runCatching { SavedViewCodec.decodeStringArray(targetIdsJson) }.getOrDefault(emptyList())

  private suspend fun loadWorkingCalendarForItem(itemId: String): PersistedWorkingCalendar {
    val mapping = planDao.getPlanItemSchedule(itemId)
    return loadWorkingCalendar(mapping?.workScheduleId)
  }

  /** Only an absent assignment may use the default; an explicit stale assignment fails closed. */
  private suspend fun loadWorkingCalendar(workScheduleId: String?): PersistedWorkingCalendar {
    val schedule =
      if (workScheduleId == null) {
        val defaults = planDao.getWorkSchedules().filter { it.isDefault }
        require(defaults.size == 1) { "Exactly one active default working schedule is required" }
        defaults.single()
      } else {
        requireNotNull(planDao.getWorkSchedule(workScheduleId)) {
          "The assigned working schedule is unavailable"
        }
      }
    require(schedule.archivedAt == null) { "The assigned working schedule is unavailable" }
    return WorkingCalendarMapper.fromEntities(
      schedule,
      planDao.getWorkScheduleWindows(schedule.id),
    )
  }

  private suspend fun wouldCreateParentCycle(itemId: String, parentId: String): Boolean {
    var cursor: String? = parentId
    val visited = mutableSetOf<String>()
    while (cursor != null && visited.add(cursor)) {
      if (cursor == itemId) return true
      cursor = planDao.getItem(cursor)?.parentId
    }
    return false
  }

  /** Returns each selected parent/child component once, in selection order and stable row order. */
  private fun atomicMoveComponents(
    items: List<PlanItem>,
    requestedItemIds: Set<String>,
  ): List<List<PlanItem>> {
    val byId = items.associateBy(PlanItem::id)
    require(requestedItemIds.all(byId::containsKey)) { "A selected task is unavailable" }
    val invalidHierarchyIds = linkedSetOf<String>()
    val adjacent = mutableMapOf<String, MutableSet<String>>()
    items.forEach { item ->
      val parentId = item.parentId ?: return@forEach
      val parent = byId[parentId]
      if (parent == null || parent.boardId != item.boardId) {
        invalidHierarchyIds += item.id
      } else {
        adjacent.getOrPut(item.id, ::linkedSetOf).add(parent.id)
        adjacent.getOrPut(parent.id, ::linkedSetOf).add(item.id)
      }
    }

    val handled = mutableSetOf<String>()
    return buildList {
      requestedItemIds.forEach { selectedId ->
        if (selectedId in handled) return@forEach
        val queue = ArrayDeque<String>().apply { add(selectedId) }
        val componentIds = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
          val id = queue.removeFirst()
          if (!componentIds.add(id)) continue
          adjacent[id].orEmpty().forEach(queue::add)
        }
        handled += componentIds
        require(componentIds.none(invalidHierarchyIds::contains)) {
          "The selected hierarchy is incomplete"
        }
        val component = componentIds.map(byId::getValue)
        require(component.map(PlanItem::columnId).distinct().size == 1) {
          "The selected hierarchy spans workflow sections"
        }
        require(!hierarchyHasCycle(component, byId)) {
          "The selected hierarchy contains a cycle"
        }
        add(
          component.sortedWith(
            compareBy<PlanItem> { it.rank }.thenBy { it.createdAt }.thenBy { it.id }
          )
        )
      }
    }
  }

  private fun hierarchyHasCycle(
    component: List<PlanItem>,
    byId: Map<String, PlanItem>,
  ): Boolean {
    val componentIds = component.mapTo(linkedSetOf(), PlanItem::id)
    return component.any { start ->
      val path = mutableSetOf<String>()
      var cursor: String? = start.id
      while (cursor != null && cursor in componentIds) {
        if (!path.add(cursor)) return@any true
        cursor = byId[cursor]?.parentId
      }
      false
    }
  }

  /** Every ancestor, descendant, and sibling connected through parent links. */
  private fun hierarchyComponent(items: List<PlanItem>, seedId: String): List<PlanItem> {
    val byId = items.associateBy { it.id }
    val adjacent = mutableMapOf<String, MutableSet<String>>()
    items.forEach { item ->
      val parentId = item.parentId?.takeIf(byId::containsKey) ?: return@forEach
      adjacent.getOrPut(item.id, ::mutableSetOf).add(parentId)
      adjacent.getOrPut(parentId, ::mutableSetOf).add(item.id)
    }
    val queue = ArrayDeque<String>().apply { add(seedId) }
    val visited = mutableSetOf<String>()
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      if (!visited.add(current)) continue
      adjacent[current].orEmpty().forEach(queue::add)
    }
    return items.filter { it.id in visited }
  }

  companion object {
    val DEFAULT_BOARD_ID: String = LegacyPlanCatalogBuilder.stableId("legacy-board:default")

    internal fun hasPath(
      dependencies: List<PlanDependency>,
      from: String,
      to: String,
    ): Boolean {
      val outgoing = dependencies.groupBy { it.predecessorId }
      val queue = ArrayDeque<String>().apply { add(from) }
      val visited = mutableSetOf<String>()
      while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) continue
        if (current == to) return true
        outgoing[current].orEmpty().forEach { queue.add(it.successorId) }
      }
      return false
    }

    private const val RANK_GAP = 1_000_000L
    private const val MAX_WORK_SCHEDULE_NAME_LENGTH = 80
    /** Kept for callers that need a floor; the configured window is read per mutation. */
    internal const val MINIMUM_UNDO_WINDOW_MS = 30_000L
  }
}
