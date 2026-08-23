import { describe, expect, it } from "vitest";

import { removeReplaceableBlocks, toPlanningItems, toScheduledBlocks } from "./planner-contract";

describe("mobile planner replan contract", () => {
  it("preserves workspace project ownership when planning multiple projects", () => {
    expect(
      toPlanningItems([
        { id: "task-a", boardId: "project-a", title: "A", rank: 1 },
        { id: "task-b", boardId: "project-b", title: "B", rank: 2 },
      ]),
    ).toMatchObject([
      { id: "task-a", boardId: "project-a" },
      { id: "task-b", boardId: "project-b" },
    ]);
  });

  it("preserves persisted identity when mapping operational blocks", () => {
    expect(
      toScheduledBlocks([
        {
          id: "block/1",
          projectId: "project-a",
          taskId: "task/1",
          startAt: 100,
          endAt: 200,
          position: 3,
          locked: false,
          linkedEventId: "event/1",
        },
      ]),
    ).toEqual([
      {
        id: "block/1",
        planItemId: "task/1",
        startAt: 100,
        endAt: 200,
        position: 3,
        locked: false,
        linkedEventId: "event/1",
      },
    ]);
  });

  it("removes only unlocked target blocks for replacement", () => {
    const blocks = toScheduledBlocks([
      { id: "target", projectId: "a", taskId: "task-a", startAt: 1, endAt: 2, position: 0, locked: false },
      { id: "locked", projectId: "a", taskId: "task-a", startAt: 3, endAt: 4, position: 1, locked: true },
      { id: "other", projectId: "b", taskId: "task-b", startAt: 5, endAt: 6, position: 0, locked: false },
    ]);

    expect(removeReplaceableBlocks(blocks, new Set(["task-a"])).map((block) => block.id)).toEqual([
      "locked",
      "other",
    ]);
  });
});
