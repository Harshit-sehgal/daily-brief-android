"use client";

/*
 * The notebook's one source of state.
 *
 * Every page — Today, Week, Projects, Settings — reads the same planner, so a
 * proposal drafted on Today is still on the desk when you turn to the Week, and a
 * "waits for" chosen on a project board still shapes the next plan. It lives in
 * the root layout for that reason: the layout survives navigation and a page does
 * not. The server stays authoritative for Apply; this only composes requests and
 * holds what came back.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import { ApiError, client, leaveForGoogle, savedSession, saveSession } from "./api";
import { forgetDevice, loadOutbox, loadSnapshot, saveOutbox, saveSnapshot, type Snapshot } from "./offline";
import { addDays, startOfDay, weekStart } from "./format";
import { findClashes } from "./day-layout";
import { weeklyWindows } from "./working-schedule";
import type { Jot } from "./jot";
import type {
  BaselineComparison,
  BaselineSnapshot,
  BillingResponse,
  Board,
  CapacityResponse,
  Connections,
  ExternalEvent,
  PlanBlock,
  PlanningRequest,
  PlanRun,
  PortfolioResponse,
  Project,
  ScenarioResponse,
  SummaryResponse,
  Task,
  TaskPatch,
  TodayResponse,
  WorkSchedule,
  WorkWindow,
} from "./types";

// Fixture mode is for the automated journey only. Production must opt in to it explicitly;
// an omitted build variable must never silently ship a demo sign-in path.
export const FIXTURE = process.env.NEXT_PUBLIC_FIXTURE === "1";

const DAY_MS = 86_400_000;

function weekAt(now: number, offset: number) {
  const start = addDays(weekStart(now), 7 * offset);
  return { start, days: Array.from({ length: 7 }, (_, i) => addDays(start, i)) };
}

export type Action =
  | "sign-in"
  | "plan-week"
  | "plan-day"
  | "apply"
  | "discard"
  | "undo"
  | "capture"
  | "add-task"
  | "create-project"
  | "connect"
  | "compare"
  | "capacity"
  | "baseline"
  | "variance"
  | "brief"
  | "save-hours"
  | "sign-out"
  | "jot"
  | "place"
  | "delete-account";

/** What is in the hand. The pen writes and ticks, the pencil drafts a place in the week,
 *  the highlighter marks what matters, the eraser rubs a line out. */
export type Tool = "pen" | "pencil" | "highlighter" | "eraser";

const SYNC_EVERY_MS = 10 * 60_000;
const DRIVE_SETTLE_MS = 12_000;

/** Where the proposal on the desk came from: a whole-week or whole-day plan, or one
 *  task dropped on one day — which is answered in that day, not at the top of the page. */
export type RunOrigin = { kind: "week" } | { kind: "day" } | { kind: "drop"; day: number; taskId: string };

/** A block of your own work on the week, with the names a page needs to print it. */
export interface WeekBlock {
  itemId: string;
  title: string;
  projectName: string;
  startAt: number;
  endAt: number;
}

export interface NewTask {
  title: string;
  effortMinutes?: number;
  dueAt?: number;
}

function useSessionWorkspace(): string | null | undefined {
  // undefined on the server and during hydration: the session lives in localStorage,
  // so the server cannot know it, and rendering "signed out" there would flash a
  // sign-in form at every signed-in reader and mismatch the client's first render.
  return useSyncExternalStore(
    (onChange) => {
      window.addEventListener("storage", onChange);
      return () => window.removeEventListener("storage", onChange);
    },
    () => savedSession()?.workspaceId ?? null,
    () => undefined,
  );
}

function usePlannerState() {
  const workspaceId = useSessionWorkspace();
  const status: "unknown" | "signed-out" | "signed-in" =
    workspaceId === undefined ? "unknown" : workspaceId === null ? "signed-out" : "signed-in";

  const [projects, setProjects] = useState<Project[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [board, setBoard] = useState<Board | null>(null);
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [events, setEvents] = useState<ExternalEvent[] | null>(null);
  const [connections, setConnections] = useState<Connections | null>(null);
  const [tool, setTool] = useState<Tool>("pen");
  const [erased, setErased] = useState<{ id: string; title: string } | null>(null);
  const [openTaskId, setOpenTaskId] = useState<string | null>(null);
  // Which week the Week page is open at: 0 is this one, 1 the next. A reader plans
  // Monday's work on a Friday, so the page has to turn.
  const [weekOffset, setWeekOffset] = useState(0);
  const [syncing, setSyncing] = useState(false);
  const [online, setOnline] = useState(true);
  const [waiting, setWaiting] = useState(0);
  const [changes, setChanges] = useState(0);
  const touch = useCallback(() => setChanges((n) => n + 1), []);
  const [schedule, setSchedule] = useState<WorkSchedule | null>(null);
  const [planBlocks, setPlanBlocks] = useState<PlanBlock[]>([]);
  const [portfolio, setPortfolio] = useState<PortfolioResponse | null>(null);
  const [baselines, setBaselines] = useState<BaselineSnapshot[] | null>(null);
  const [billing, setBilling] = useState<BillingResponse | null>(null);

  const [run, setRun] = useState<PlanRun | null>(null);
  const [runOrigin, setRunOrigin] = useState<RunOrigin>({ kind: "week" });
  const [applied, setApplied] = useState<{ entryId: string; blocks: number } | null>(null);
  const [busy, setBusy] = useState<Action | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [capacity, setCapacity] = useState<CapacityResponse | null>(null);
  const [summary, setSummary] = useState<SummaryResponse | null>(null);
  const [waitsFor, setWaitsFor] = useState<Record<string, string>>({});
  const [scenarios, setScenarios] = useState<ScenarioResponse | null>(null);
  const [preferredOrder, setPreferredOrder] = useState<{ key: string; name: string; order: string[] } | null>(null);
  const [variance, setVariance] = useState<{ baselineId: string; comparison: BaselineComparison } | null>(null);

  // The notebook's clock. Held as state so a render is a pure function of it, and
  // ticked each minute so "today" turns over at midnight without a reload.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(id);
  }, []);

  // Reads can overlap — one on opening, one after an edit, one on returning to the tab —
  // and answer out of order. Only the newest read may write to the page: an older one
  // carries the notebook as it was before the edit, and would quietly untick a task
  // the reader had just ticked.
  // Tasks jotted but not yet acknowledged by the server. A jot is on the page at once,
  // so it can be ticked or highlighted before its own save has landed; an edit waits
  // here for that save, or it would reach the server first and find no such task.
  const saving = useRef(new Map<string, Promise<void>>());
  const reads = useRef(0);
  const refresh = useCallback(async () => {
    const read = ++reads.current;
    const t = Date.now();
    // The open week, and a week ahead of today for "Place on…" and next week's plan.
    const from = weekStart(t);
    const to = Math.max(addDays(from, 7), addDays(t, 8));
    const [ps, td, pf, blocks, allTasks, planningSettings, evs, cx, bl, bi] = await Promise.all([
      client.listProjects(),
      client.today(),
      client.portfolio(),
      client.planBlocks(),
      client.listTasks(),
      client.planningSettings(),
      client.events(from, to),
      client.connections().catch(() => null),
      client.listBaselines().catch(() => null),
      client.billing().catch(() => null),
    ]);
    if (read !== reads.current) return;
    setProjects(ps);
    setTasks(allTasks);
    setToday(td);
    setSchedule(planningSettings);
    setPortfolio(pf);
    setPlanBlocks(blocks);
    setEvents(evs);
    setConnections(cx);
    setBaselines(bl);
    setBilling(bi);
    setSelectedId((current) => current ?? ps.find((p) => p.isDefault)?.id ?? ps[0]?.id ?? null);
    if (workspaceId) {
      saveSnapshot(workspaceId, { projects: ps, tasks: allTasks, today: td, schedule: planningSettings, portfolio: pf, planBlocks: blocks, events: evs, connections: cx });
    }
  }, [workspaceId]);

  /** Jots written while offline, sent in the order they were written. */
  const flushOutbox = useCallback(async () => {
    const queue = loadOutbox();
    if (queue.length === 0) return;
    let sent = 0;
    for (const item of queue) {
      try {
        if (item.projectId) await client.addProjectTask(item.projectId, item.task);
        else await client.addTask(item.task);
        sent += 1;
      } catch (e) {
        // Still offline: keep the rest. Refused by the server: drop it, or it blocks the queue for good.
        if (!(e instanceof ApiError)) break;
        sent += 1;
      }
    }
    saveOutbox(queue.slice(sent));
    setWaiting(queue.length - sent);
  }, []);

  useEffect(() => {
    if (status !== "signed-in" || !workspaceId) return;
    // Last visit's pages first, so the notebook opens written rather than blank — and
    // opens at all with no signal. The network's answer replaces them a moment later.
    const kept: Snapshot | null = loadSnapshot(workspaceId);
    if (kept) {
      setProjects((v) => v ?? kept.projects);
      // Jots still waiting to be sent are part of the page: reopening the notebook
      // offline must show them, or they would look lost.
      const inboxId = kept.projects.find((x) => x.isDefault)?.id ?? "b1";
      const unsent = loadOutbox()
        .filter((w) => !kept.tasks.some((t) => t.id === w.task.id))
        .map((w) => ({ ...w.task, boardId: w.projectId ?? inboxId }));
      setTasks((v) => v ?? [...unsent, ...kept.tasks]);
      setToday((v) => v ?? kept.today);
      setSchedule((v) => v ?? kept.schedule);
      setPortfolio((v) => v ?? kept.portfolio);
      setPlanBlocks((v) => (v.length ? v : kept.planBlocks));
      setEvents((v) => v ?? kept.events);
      setConnections((v) => v ?? kept.connections);
      setSelectedId((v) => v ?? kept.projects.find((p) => p.isDefault)?.id ?? kept.projects[0]?.id ?? null);
    }
    setWaiting(loadOutbox().length);
    flushOutbox()
      .then(refresh)
      .catch((e) => {
        // With kept pages on screen, being offline is a mark by the wordmark, not an error.
        if (!kept || e instanceof ApiError) setError(e instanceof Error ? e.message : "The notebook could not load.");
      });
  }, [status, workspaceId, refresh, flushOutbox]);

  useEffect(() => {
    const read = () => setOnline(navigator.onLine);
    const back = () => {
      read();
      if (status === "signed-in") flushOutbox().then(refresh).catch(() => {});
    };
    read();
    window.addEventListener("online", back);
    window.addEventListener("offline", read);
    return () => {
      window.removeEventListener("online", back);
      window.removeEventListener("offline", read);
    };
  }, [status, flushOutbox, refresh]);

  // A week other than the open range needs its own calendar; what comes back joins
  // the events already held, so turning back costs nothing.
  useEffect(() => {
    if (status !== "signed-in" || weekOffset === 0) return;
    let current = true;
    const from = addDays(weekStart(Date.now()), 7 * weekOffset);
    client
      .events(from, addDays(from, 7))
      .then((more) => {
        if (!current) return;
        setEvents((held) => {
          const ids = new Set(more.map((e) => e.id));
          return [...(held ?? []).filter((e) => !ids.has(e.id)), ...more];
        });
      })
      .catch(() => {});
    return () => {
      current = false;
    };
  }, [status, weekOffset]);

  // A notebook left open on one device picks up what was written on another as soon as
  // it is looked at again: returning to the tab re-reads the pages if they are over half
  // a minute old.
  useEffect(() => {
    if (status !== "signed-in") return;
    let readAt = Date.now();
    const reread = () => {
      if (document.visibilityState !== "visible" || !navigator.onLine || Date.now() - readAt < 30_000) return;
      readAt = Date.now();
      refresh().catch(() => {});
    };
    document.addEventListener("visibilitychange", reread);
    window.addEventListener("focus", reread);
    return () => {
      document.removeEventListener("visibilitychange", reread);
      window.removeEventListener("focus", reread);
    };
  }, [status, refresh]);

  // The calendar keeps itself current: fetched again when the notebook is opened or
  // returned to and the last fetch is more than ten minutes old. Nobody presses Sync.
  const calendarConnected = connections?.calendar.connected ?? false;
  const lastSyncAt = connections?.calendar.lastSyncAt ?? null;
  useEffect(() => {
    if (status !== "signed-in" || !calendarConnected) return;
    let running = false;
    const maybe = async () => {
      if (running || document.visibilityState !== "visible" || !navigator.onLine) return;
      if (lastSyncAt !== null && Date.now() - lastSyncAt < SYNC_EVERY_MS) return;
      running = true;
      setSyncing(true);
      try {
        await client.reconcile();
        await refresh();
      } catch {
        /* a missed background sync is retried on the next visit; it is not the reader's problem */
      } finally {
        running = false;
        setSyncing(false);
      }
    };
    void maybe();
    document.addEventListener("visibilitychange", maybe);
    window.addEventListener("focus", maybe);
    return () => {
      document.removeEventListener("visibilitychange", maybe);
      window.removeEventListener("focus", maybe);
    };
  }, [status, calendarConnected, lastSyncAt, refresh]);

  // The Drive copy follows the notebook: rewritten a few seconds after the last change,
  // so a burst of jotting is one upload.
  const driveConnected = connections?.drive.connected ?? false;
  const planConnected = connections?.planCalendar?.connected ?? false;
  useEffect(() => {
    if (changes === 0 || (!driveConnected && !planConnected)) return;
    const id = window.setTimeout(() => {
      if (driveConnected) {
        client
          .driveBackup()
          .then((drive) => setConnections((c) => (c ? { ...c, drive } : c)))
          .catch(() => {});
      }
      // The plan in Google Calendar follows the same way: what was applied, undone,
      // finished or rubbed out is there a few seconds later.
      if (planConnected) {
        client
          .pushPlan()
          .then((planCalendar) => setConnections((c) => (c ? { ...c, planCalendar } : c)))
          .catch(() => {});
      }
    }, DRIVE_SETTLE_MS);
    return () => window.clearTimeout(id);
  }, [changes, driveConnected, planConnected]);

  useEffect(() => {
    if (!selectedId) return;
    let live = true;
    client
      .board(selectedId)
      .then((b) => live && setBoard(b))
      .catch((e) => live && setError(e instanceof Error ? e.message : "That project could not open."));
    return () => {
      live = false;
    };
  }, [selectedId]);

  /** Runs one action at a time, holds its name so its own button can say what it is
   *  doing, and turns any failure into a sentence on the page. */
  const act = useCallback(async <T,>(action: Action, failure: string, work: () => Promise<T>) => {
    setBusy(action);
    setError(null);
    try {
      return await work();
    } catch (e) {
      setError(e instanceof Error ? `${failure}: ${e.message}` : failure);
      return undefined;
    } finally {
      setBusy(null);
    }
  }, []);

  /**
   * The client composes the plan request: the server stays a pure planner, and the
   * calendar's busy time is the client's reading of it. The range starts at local
   * midnight and `nowMs` is the next quarter hour, so nothing is ever planned into the
   * part of today that has already gone.
   */
  function buildRequest(now: number, horizonDays = 7): PlanningRequest {
    const dayStart = startOfDay(now);
    const rangeEnd = addDays(dayStart, horizonDays);
    const quarter = 15 * 60_000;
    return {
      v: 1,
      workspaceId: workspaceId ?? "",
      rangeStartMs: dayStart,
      rangeEndMs: rangeEnd,
      nowMs: Math.ceil(now / quarter) * quarter,
      items:
        tasks
          ?.filter((t) => !t.completedAt && !t.archivedAt)
          .map((t, i) => ({
            ...t,
            boardId: t.boardId ?? "b1",
            rank: t.rank ?? i,
            effortMinutes: t.effortMinutes ?? undefined,
          })) ?? [],
      blocks: planBlocks.map((block) => ({
        id: block.id,
        planItemId: block.taskId,
        startAt: block.startAt,
        endAt: block.endAt,
        position: block.position,
        locked: block.locked,
        linkedEventId: block.linkedEventId ?? null,
      })),
      // An all-day event is a mark on the day — a birthday, a holiday — not busy hours.
      // Passing it as fixed time told the planner the whole day was taken, so nothing
      // could ever be placed on a day with a birthday on it.
      fixedCommitments: (events ?? [])
        .filter((e) => !e.isAllDay && e.endTime > dayStart && e.startTime < rangeEnd)
        .map((e) => ({ startAt: e.startTime, endAt: e.endTime })),
      dependencies: Object.entries(waitsFor)
        .filter(([successorId, predecessorId]) =>
          tasks?.some((t) => t.id === successorId) && tasks?.some((t) => t.id === predecessorId),
        )
        .map(([successorId, predecessorId], i) => ({
          id: `d${i}`,
          predecessorId,
          successorId,
          type: "finish_to_start",
          lagMinutes: 0,
        })),
      scheduleIdByTaskId: {},
      preferredOrder: preferredOrder?.order ?? [],
      schedules: schedule ? [schedule] : [],
      deadlinePolicy: "HARD",
    };
  }

  // Nothing to plan until there is a task that is not done.
  const canPlan = tasks !== null && events !== null && schedule !== null && online && tasks.some((t) => !t.completedAt && !t.archivedAt);

  /** Changes one task on the page at once and on the server behind it; a refusal puts it back. */
  async function editTask(taskId: string, patch: TaskPatch) {
    const before = { tasks, board };
    const apply = (t: Task): Task =>
      t.id !== taskId
        ? t
        : {
            ...t,
            ...("title" in patch ? { title: patch.title! } : {}),
            ...("effortMinutes" in patch ? { effortMinutes: patch.effortMinutes } : {}),
            ...("dueAt" in patch ? { dueAt: patch.dueAt } : {}),
            ...("priority" in patch ? { priority: patch.priority } : {}),
            ...("completed" in patch ? { completedAt: patch.completed ? Date.now() : null } : {}),
          };
    const keep = (list: Task[]) => (patch.archived ? list.filter((t) => t.id !== taskId) : list.map(apply));
    setTasks((list) => (list ? keep(list) : list));
    setBoard((b) => (b ? { ...b, tasks: keep(b.tasks) } : b));
    setError(null);
    try {
      await saving.current.get(taskId);
      await client.patchTask(taskId, patch);
      touch();
      // Read the notebook back: finishing or erasing also takes the task's blocks off
      // the plan ahead, and a read started before this edit must not be the last word.
      // The edit is saved either way: a failed read-back is not a failed edit.
      await refresh().catch(() => {});
      return true;
    } catch (e) {
      setTasks(before.tasks);
      setBoard(before.board);
      setError(e instanceof ApiError ? `That change was not saved: ${e.message}` : "You are offline, so that change was not saved.");
      return false;
    }
  }

  const actions = {
    async signIn() {
      await act("sign-in", "Sign-in failed", async () => {
        if (FIXTURE) {
          saveSession(await client.signup());
          window.location.reload();
        } else {
          const { url } = await client.authStart();
          leaveForGoogle(url, "/");
        }
      });
    },

    async plan(horizon: "week" | "day") {
      if (!canPlan) return;
      setRun(null);
      const now = Date.now();
      const ahead = horizon === "week" && weekOffset > 0;
      const base = buildRequest(now, horizon === "week" ? 7 : 1);
      // Planning a week ahead fills that week with what has no place yet. It must not
      // replan: that would lift this week's blocks and set them down in the next.
      const placed = new Set(planBlocks.map((b) => b.taskId));
      const from = addDays(weekStart(now), 7 * weekOffset);
      const request: PlanningRequest = ahead
        ? {
            ...base,
            rangeStartMs: from,
            rangeEndMs: addDays(from, 7),
            nowMs: from,
            items: base.items.filter((t) => !placed.has(t.id)),
            fixedCommitments: [
              ...(events ?? []).filter((e) => !e.isAllDay && e.endTime > from && e.startTime < addDays(from, 7)).map((e) => ({ startAt: e.startTime, endAt: e.endTime })),
              ...planBlocks.map((b) => ({ startAt: b.startAt, endAt: b.endAt })),
            ],
            blocks: [],
          }
        : base;
      const next = await act(horizon === "week" ? "plan-week" : "plan-day", "Planning failed", () =>
        client.plan(request, !ahead && planBlocks.length > 0),
      );
      if (next) {
        setRun(next);
        setRunOrigin({ kind: horizon });
      }
    },

    /**
     * One task, pencilled into one day — or, with no day given, into the first place it
     * fits in the week ahead. The planner is asked for just that task, around the
     * calendar *and* every block already placed (passed as fixed time, so nothing else
     * can move). Like every plan it comes back in pencil; Apply is the only thing that writes.
     */
    async placeOnDay(taskId: string, day?: number) {
      const task = tasks?.find((t) => t.id === taskId);
      if (!task || !schedule) return;
      const now = Date.now();
      const dayStart = day === undefined ? startOfDay(now) : startOfDay(day);
      const dayEnd = addDays(dayStart, day === undefined ? 7 : 1);
      if (dayEnd <= now) {
        setError("That day has already gone. Choose today or a day ahead.");
        return;
      }
      setRun(null);
      const base = buildRequest(now);
      const request: PlanningRequest = {
        ...base,
        rangeStartMs: dayStart,
        rangeEndMs: dayEnd,
        nowMs: Math.max(base.nowMs, dayStart),
        items: [{ ...task, boardId: task.boardId ?? "b1", rank: task.rank ?? 0, effortMinutes: task.effortMinutes ?? undefined }],
        blocks: base.blocks.filter((b) => b.planItemId === taskId),
        fixedCommitments: [
          ...base.fixedCommitments,
          ...planBlocks.filter((b) => b.taskId !== taskId).map((b) => ({ startAt: b.startAt, endAt: b.endAt })),
        ],
        dependencies: [],
        preferredOrder: [],
      };
      const next = await act("place", "Placing the task failed", () => client.plan(request, false));
      if (next) {
        setRun(next);
        // The answer is written in the day the task landed on, or the one it was sent to.
        const landed = next.result.proposals[0]?.startAt;
        setRunOrigin({ kind: "drop", day: landed !== undefined ? startOfDay(landed) : dayStart, taskId });
      }
    },

    async apply() {
      if (!run) return;
      const res = await act("apply", "Apply failed", async () => {
        const out = await client.apply(run.runId);
        await refresh();
        return out;
      });
      if (res) {
        setApplied({ entryId: res.entryId, blocks: res.blocks });
        setRun(null);
        touch();
      }
    },

    async discard() {
      if (!run) return;
      const ok = await act("discard", "Discard failed", () => client.reject(run.runId));
      if (ok) setRun(null);
    },

    async undo() {
      if (!applied) return;
      const ok = await act("undo", "Undo failed", async () => {
        await client.undo(applied.entryId);
        await refresh();
        return true;
      });
      if (ok) {
        setApplied(null);
        setRun(null);
        touch();
      }
    },

    /**
     * Jotted lines, read by `parseJot`: each goes to the inbox, or to the project its
     * #tag named. The line is on the page before the server has answered — a jot must
     * never wait on the network — and with no signal it is kept and sent later.
     */
    async jot(jots: Jot[]) {
      const lines = jots.filter((j) => j.title.trim());
      if (lines.length === 0) return 0;
      const inboxId = projects?.find((x) => x.isDefault)?.id ?? "b1";
      const written = lines.map((j) => {
        const id = crypto.randomUUID().slice(0, 8);
        const task: Task = {
          id: j.project ? `t${id}` : `inbox-${id}`,
          boardId: j.project?.id ?? "b1",
          columnId: null,
          title: j.title.trim(),
          rank: 0,
          effortMinutes: j.effortMinutes,
          dueAt: j.dueAt,
        };
        return { projectId: j.project?.id ?? null, task };
      });
      setError(null);
      setTasks((list) => [...written.map((w) => ({ ...w.task, boardId: w.projectId ?? inboxId })), ...(list ?? [])]);
      const send = async () => {
        const unsent: typeof written = [];
        for (const w of written) {
          try {
            if (w.projectId) await client.addProjectTask(w.projectId, w.task);
            else await client.addTask(w.task);
          } catch (e) {
            if (e instanceof ApiError) {
              setTasks((list) => list?.filter((t) => t.id !== w.task.id) ?? list);
              setError(`“${w.task.title}” was not saved: ${e.message}`);
            } else unsent.push(w);
          }
        }
        if (unsent.length > 0) {
          const queue = [...loadOutbox(), ...unsent];
          saveOutbox(queue);
          setWaiting(queue.length);
        }
        return unsent.length === 0;
      };
      const sent = send();
      const landed = sent.then(() => undefined);
      for (const w of written) saving.current.set(w.task.id, landed);
      void sent
        .then(async (all) => {
          if (!all) return;
          touch();
          await refresh().catch(() => {});
          if (selectedId && written.some((w) => w.projectId === selectedId)) setBoard(await client.board(selectedId));
        })
        .finally(() => {
          for (const w of written) saving.current.delete(w.task.id);
        });
      return lines.length;
    },

    editTask,

    /** Tick a task done, or untick it. */
    tick(taskId: string, done: boolean) {
      return editTask(taskId, { completed: done });
    },

    highlight(taskId: string, on: boolean) {
      return editTask(taskId, { priority: on ? "high" : "normal" });
    },

    /** Rub a task out. It can be written back until the next one is rubbed out. */
    async erase(taskId: string) {
      const title = tasks?.find((t) => t.id === taskId)?.title ?? "That task";
      if (await editTask(taskId, { archived: true })) setErased({ id: taskId, title });
    },

    async restore() {
      if (!erased) return;
      const ok = await act("undo", "Writing it back failed", async () => {
        await client.patchTask(erased.id, { archived: false });
        await refresh();
        if (selectedId) setBoard(await client.board(selectedId));
        return true;
      });
      if (ok) setErased(null);
    },

    forgetErased() {
      setErased(null);
    },

    /** Give a task its estimate. */
    setEffort(taskId: string, effortMinutes: number | null) {
      return editTask(taskId, { effortMinutes });
    },

    /**
     * What a click on a task does with the tool in hand. The pen leaves it to the caller
     * (which opens the task's card); the other three act at once, as they would on paper.
     */
    applyTool(taskId: string): boolean {
      const task = tasks?.find((t) => t.id === taskId);
      if (!task || tool === "pen") return false;
      if (tool === "highlighter") void editTask(taskId, { priority: task.priority === "high" ? "normal" : "high" });
      else if (tool === "eraser") void actions.erase(taskId);
      else if (!task.completedAt) void actions.placeOnDay(taskId);
      return true;
    },

    setTool,

    /** A click on a task: the tool in hand acts on it, and the pen opens its card. */
    touchTask(taskId: string) {
      if (!actions.applyTool(taskId)) setOpenTaskId(taskId);
    },

    closeTask() {
      setOpenTaskId(null);
    },

    async addTask(task: NewTask) {
      const projectId = selectedId;
      if (!task.title.trim() || !projectId) return false;
      const ok = await act("add-task", "Adding the task failed", async () => {
        await client.addProjectTask(projectId, {
          id: `t${crypto.randomUUID().slice(0, 8)}`,
          boardId: projectId,
          columnId: null,
          title: task.title.trim(),
          rank: board?.tasks.length ?? 0,
          effortMinutes: task.effortMinutes,
          dueAt: task.dueAt,
        });
        const [nextBoard, allTasks, pf] = await Promise.all([
          client.board(projectId),
          client.listTasks(),
          client.portfolio(),
        ]);
        setBoard(nextBoard);
        setTasks(allTasks);
        setPortfolio(pf);
        touch();
        return true;
      });
      return Boolean(ok);
    },

    async createProject(name: string) {
      const trimmed = name.trim();
      if (!trimmed) return false;
      const created = await act("create-project", "Creating the project failed", async () => {
        const project = await client.createProject(trimmed);
        const [ps, pf] = await Promise.all([client.listProjects(), client.portfolio()]);
        setProjects(ps);
        setPortfolio(pf);
        touch();
        return project;
      });
      if (created) setSelectedId(created.id);
      return Boolean(created);
    },

    selectProject(id: string) {
      setSelectedId(id);
    },

    setWaitsFor(taskId: string, predecessorId: string | null) {
      setWaitsFor((prev) => {
        const next = { ...prev };
        if (predecessorId) next[taskId] = predecessorId;
        else delete next[taskId];
        return next;
      });
    },

    /**
     * Connect the calendar or Drive. Each is its own Google consent, asked for when the
     * reader asks for the thing; signing in asks only who they are.
     */
    async connect(what: "calendar" | "drive" | "plan") {
      await act("connect", what === "drive" ? "Connecting Drive failed" : "Connecting the calendar failed", async () => {
        if (FIXTURE) {
          if (what === "calendar") await client.reconcile();
          else if (what === "plan") await client.pushPlan();
          else await client.driveBackup();
          await refresh();
        } else {
          const { url } = await client.authStart(what);
          // Consent comes back through /auth/callback; return to the page it was asked from.
          leaveForGoogle(url, window.location.pathname);
        }
      });
    },

    /** Fetch the calendar now, for a reader who has just changed something in it. */
    async syncNow() {
      await act("connect", "Fetching the calendar failed", async () => {
        await client.reconcile();
        await refresh();
      });
    },

    async compare() {
      if (!schedule) return;
      const res = await act("compare", "Comparing approaches failed", () => client.scenarios(buildRequest(Date.now())));
      if (res) setScenarios(res);
    },

    chooseOrder(key: string, name: string, order: string[]) {
      // "Earliest due first" is the planner's own default, so it clears the override.
      setPreferredOrder(key === "due" ? null : { key, name, order });
    },

    async checkCapacity() {
      if (!schedule) return;
      const res = await act("capacity", "Checking capacity failed", () =>
        client.capacity({ v: 1, plan: buildRequest(Date.now()), newClientHoursPerWeek: 8 }),
      );
      if (res) setCapacity(res);
    },

    async takeBaseline() {
      await act("baseline", "Taking a baseline failed", async () => {
        await client.createBaseline();
        setBaselines(await client.listBaselines());
        setVariance(null);
      });
    },

    async compareBaseline(baselineId: string) {
      const res = await act("variance", "Comparing with the baseline failed", () => client.baselineVariance(baselineId));
      if (res) setVariance({ baselineId, comparison: res });
    },

    async writeBrief() {
      const res = await act("brief", "Writing the brief failed", () => client.summary());
      if (res) setSummary(res);
    },

    async saveHours(next: WorkSchedule) {
      const saved = await act("save-hours", "Saving working hours failed", () => client.updatePlanningSettings(next));
      if (saved) {
        setSchedule(saved);
        touch();
      }
      return Boolean(saved);
    },

    async signOut() {
      setBusy("sign-out");
      try {
        await client.logout();
      } catch {
        // Clear local state even if the API is unavailable; the cookie is cleared on the
        // normal path, while a failed network call cannot leave a bearer token in storage.
      } finally {
        // Nothing of the notebook stays on a device its reader has signed out of.
        forgetDevice();
        window.location.reload();
      }
    },

    /** Remove the account and everything in it, here and at Google. */
    async deleteAccount() {
      const gone = await act("delete-account", "Deleting the account failed", async () => {
        await client.deleteAccount();
        await client.logout().catch(() => {});
        return true;
      });
      if (gone) {
        forgetDevice();
        window.location.replace(window.location.origin);
      }
    },

    dismissError() {
      setError(null);
    },
  };

  // ── Derived readings every page shares ──────────────────────────────────
  const derived = useMemo(() => {
    const titles = new Map((tasks ?? []).map((t) => [t.id, t.title]));
    const projectNames = new Map((projects ?? []).map((p) => [p.id, p.name]));
    const dayStart = startOfDay(now);
    // When each task is first booked: a task with a place in the plan is on its day.
    const firstBlock = new Map<string, number>();
    for (const b of planBlocks) {
      const seen = firstBlock.get(b.taskId);
      if (seen === undefined || b.startAt < seen) firstBlock.set(b.taskId, b.startAt);
    }
    // The inbox is what still needs a place. A task the planner has booked is written
    // on its day, and listing it here as well only made the list longer. A task ticked
    // today stays, struck through, until the day turns over: a line that vanished on
    // the tick would look lost rather than done.
    const toPlace = (tasks ?? [])
      .filter((t) => !t.columnId && !t.archivedAt && !t.completedAt && !firstBlock.has(t.id))
      // What was highlighted first, then what is due soonest; otherwise as written.
      .map((t, i) => ({ t, i }))
      .sort(
        (a, b) =>
          Number(b.t.priority === "high") - Number(a.t.priority === "high") ||
          (a.t.dueAt ?? Infinity) - (b.t.dueAt ?? Infinity) ||
          a.i - b.i,
      )
      .map((x) => x.t);
    const doneToday = (tasks ?? []).filter((t) => !t.columnId && !t.archivedAt && t.completedAt != null && t.completedAt >= dayStart && !firstBlock.has(t.id));
    const inbox = [...toPlace, ...doneToday];
    const byId = new Map((tasks ?? []).map((t) => [t.id, t]));
    const unestimated = (tasks ?? []).filter((t) => !t.completedAt && !t.archivedAt && !t.effortMinutes).length;
    // Every block of your own work, named. Read from the blocks themselves rather than
    // the portfolio's week, which is one week only and cut at UTC midnight.
    const weekBlocks: WeekBlock[] = planBlocks.map((b) => ({
      itemId: b.taskId,
      title: titles.get(b.taskId) ?? "Untitled",
      projectName: projectNames.get(b.projectId) ?? "",
      startAt: b.startAt,
      endAt: b.endAt,
    }));
    const projectOfTask = (id: string) => {
      const boardId = (tasks ?? []).find((t) => t.id === id)?.boardId;
      return boardId ? projectNames.get(boardId) ?? null : null;
    };
    return { titles, byId, inbox, toPlace, firstBlock, unestimated, weekBlocks, projectOfTask };
  }, [tasks, projects, planBlocks, now]);

  /** Working minutes the schedule allows on the local calendar day starting at `dayMs`. */
  const windowsOn = useCallback(
    (dayMs: number): WorkWindow[] => (schedule ? weeklyWindows(schedule, new Date(dayMs).getDay() + 1) : []),
    [schedule],
  );
  const capacityMinutes = useCallback(
    (dayMs: number) => windowsOn(dayMs).reduce((sum, w) => sum + Math.max(0, w.endMinute - w.startMinute), 0),
    [windowsOn],
  );

  /** Minutes of your own planned work on the local day starting at `dayMs`. */
  const plannedMinutes = useCallback(
    (dayMs: number) => {
      const next = addDays(dayMs, 1);
      return Math.round(planBlocks.reduce((sum, b) => sum + Math.max(0, Math.min(b.endAt, next) - Math.max(b.startAt, dayMs)), 0) / 60_000);
    },
    [planBlocks],
  );

  // Today's clashes, on the reader's own day: calendar events against each other and
  // against planned work.
  const clashes = useMemo(() => {
    const from = startOfDay(now);
    const to = addDays(from, 1);
    const titles = new Map((tasks ?? []).map((t) => [t.id, t.title]));
    return findClashes([
      ...(events ?? []).filter((e) => !e.isAllDay && e.startTime < to && e.endTime > from).map((e) => ({ start: e.startTime, end: e.endTime, title: e.title || "Busy" })),
      ...planBlocks.filter((b) => b.startAt < to && b.endAt > from).map((b) => ({ start: b.startAt, end: b.endAt, title: titles.get(b.taskId) ?? "Untitled" })),
    ]);
  }, [events, planBlocks, tasks, now]);

  return {
    now,
    clashes,
    status,
    workspaceId,
    loaded: tasks !== null && events !== null && portfolio !== null,
    events,
    connections,
    tool,
    openTaskId,
    erased,
    online,
    syncing,
    /** Jots written offline and not yet sent. */
    waiting,
    task: (id: string) => derived.byId.get(id) ?? null,
    projects,
    selectedId,
    board,
    tasks,
    today,
    schedule,
    planBlocks,
    portfolio,
    baselines,
    billing,
    run,
    runOrigin,
    applied,
    busy,
    error,
    capacity,
    summary,
    waitsFor,
    scenarios,
    preferredOrder,
    variance,
    canPlan,
    /** The week the Week page is open at. */
    week: weekAt(now, weekOffset),
    thisWeek: weekAt(now, 0),
    weekOffset,
    /** Turn the Week page: a month back, two months on — as far as the calendar is read. */
    turnWeek: (by: number) => setWeekOffset((o) => (by === 0 ? 0 : Math.max(-4, Math.min(8, o + by)))),
    todayStart: startOfDay(now),
    windowsOn,
    capacityMinutes,
    plannedMinutes,
    taskTitle: (id: string) => derived.titles.get(id) ?? "Untitled task",
    projectOfTask: derived.projectOfTask,
    inbox: derived.inbox,
    toPlace: derived.toPlace,
    bookedAt: (taskId: string) => derived.firstBlock.get(taskId) ?? null,
    unestimated: derived.unestimated,
    weekBlocks: derived.weekBlocks,
    ...actions,
  };
}

export type Planner = ReturnType<typeof usePlannerState>;

const PlannerContext = createContext<Planner | null>(null);

export function PlannerProvider({ children }: { children: React.ReactNode }) {
  const planner = usePlannerState();
  return <PlannerContext.Provider value={planner}>{children}</PlannerContext.Provider>;
}

export function usePlanner(): Planner {
  const planner = useContext(PlannerContext);
  if (!planner) throw new Error("usePlanner must be used inside <PlannerProvider>");
  return planner;
}
