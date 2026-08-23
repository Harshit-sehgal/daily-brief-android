import type { PlanBlock, ScheduledBlock, Task } from "./types";

/** Preserve workspace task ownership when composing a planning request. */
export function toPlanningItems(tasks: readonly Task[]): Task[] {
  return tasks.map((task, index) => ({
    ...task,
    rank: task.rank ?? index,
    effortMinutes: task.effortMinutes ?? undefined,
  }));
}

/** Convert the operational block read into the frozen planner wire shape. */
export function toScheduledBlocks(blocks: PlanBlock[]): ScheduledBlock[] {
  return blocks.map((block) => ({
    id: block.id,
    planItemId: block.taskId,
    startAt: block.startAt,
    endAt: block.endAt,
    position: block.position,
    locked: block.locked,
    linkedEventId: block.linkedEventId ?? null,
  }));
}

/** Match server replan semantics for local preview: unlocked blocks belonging to the
 *  requested tasks are replaced; locked blocks and unrelated project work stay obstacles. */
export function removeReplaceableBlocks(
  blocks: ScheduledBlock[],
  targetTaskIds: ReadonlySet<string>,
): ScheduledBlock[] {
  return blocks.filter((block) => !targetTaskIds.has(block.planItemId) || block.locked === true);
}
