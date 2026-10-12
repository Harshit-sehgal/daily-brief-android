/*
 * What the notebook keeps in the browser so it opens at once and survives a lost signal.
 *
 * The snapshot is the last set of pages the server sent: shown immediately on the next
 * visit and replaced when the network answers. The outbox holds jots written while
 * offline, in order, until they can be sent. Neither is a second source of truth — the
 * server is — and both fail silently, because a private window with no storage should
 * still be a working notebook.
 */

import type { Connections, ExternalEvent, PlanBlock, PortfolioResponse, Project, Task, TodayResponse, WorkSchedule } from "./types";

export interface Snapshot {
  projects: Project[];
  tasks: Task[];
  today: TodayResponse;
  schedule: WorkSchedule;
  portfolio: PortfolioResponse;
  planBlocks: PlanBlock[];
  events: ExternalEvent[];
  connections: Connections | null;
}

export interface OutboxItem {
  projectId: string | null;
  task: Task;
}

const SNAPSHOT_KEY = "dailybrief.snapshot";
const OUTBOX_KEY = "dailybrief.outbox";

function read<T>(key: string): T | null {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function write(key: string, value: unknown) {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* storage full or unavailable: the notebook still works, it just opens blank next time */
  }
}

/** The kept pages, only if they belong to the workspace now signed in. */
export function loadSnapshot(workspaceId: string): Snapshot | null {
  const kept = read<{ workspaceId: string; pages: Snapshot }>(SNAPSHOT_KEY);
  return kept?.workspaceId === workspaceId && kept.pages?.tasks && kept.pages.events ? kept.pages : null;
}

export function saveSnapshot(workspaceId: string, pages: Snapshot | null) {
  write(SNAPSHOT_KEY, pages ? { workspaceId, pages } : null);
}

export function loadOutbox(): OutboxItem[] {
  return read<OutboxItem[]>(OUTBOX_KEY) ?? [];
}

export function saveOutbox(items: OutboxItem[]) {
  write(OUTBOX_KEY, items.length > 0 ? items : null);
}

/**
 * Leaves nothing of the notebook on a device its reader has signed out of: the kept
 * pages, unsent jots and every day's notes go. The ink and paper they chose stay —
 * those say nothing about them.
 */
export function forgetDevice() {
  try {
    for (const key of Object.keys(window.localStorage)) {
      if (key.startsWith("dailybrief.") && key !== "dailybrief.ink" && key !== "dailybrief.paper") window.localStorage.removeItem(key);
    }
    window.sessionStorage.clear();
  } catch {
    /* nothing was kept if storage is unavailable */
  }
}
