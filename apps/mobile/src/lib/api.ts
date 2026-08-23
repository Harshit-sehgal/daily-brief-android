import { Platform } from "react-native";
import * as SecureStore from "expo-secure-store";

import { cacheKeys, readCache, writeCache } from "./offline-cache";
import { resolveApiBase } from "./api-config";
import type {
  CachedBoard,
  CachedPlanningSettings,
  CachedPortfolio,
  CachedProjects,
  CachedTasks,
  CachedToday,
} from "./offline-cache";
import type {
  ApplyResponse,
  Board,
  CapacityResponse,
  PlanRun,
  PlanBlock,
  PlanningRequest,
  PortfolioResponse,
  Project,
  SessionResponse,
  SummaryResponse,
  Task,
  TodayResponse,
  WorkSchedule,
} from "./types";

/** Android emulators reach the host machine at 10.0.2.2; simulators and web use localhost.
 *  Override with EXPO_PUBLIC_API_BASE for a real device or a deployed server. A release build
 *  must provide the deployed origin explicitly; the local fallback is development-only. */
export const API_BASE = resolveApiBase(
  process.env.EXPO_PUBLIC_API_BASE,
  process.env.NODE_ENV,
  Platform.OS,
);

const TOKEN_KEY = "dailybrief.token";
const WORKSPACE_KEY = "dailybrief.workspaceId";

async function read(key: string): Promise<string | null> {
  if (Platform.OS === "web") {
    try {
      return window.localStorage.getItem(key);
    } catch {
      return null;
    }
  }
  return SecureStore.getItemAsync(key);
}

async function write(key: string, value: string) {
  if (Platform.OS === "web") {
    try {
      window.localStorage.setItem(key, value);
    } catch {
      /* storage unavailable; session simply does not persist */
    }
    return;
  }
  await SecureStore.setItemAsync(key, value);
}

async function remove(key: string) {
  if (Platform.OS === "web") {
    try {
      window.localStorage.removeItem(key);
    } catch {
      /* nothing to do */
    }
    return;
  }
  await SecureStore.deleteItemAsync(key);
}

export async function savedSession(): Promise<{ token: string; workspaceId: string } | null> {
  const token = await read(TOKEN_KEY);
  const workspaceId = await read(WORKSPACE_KEY);
  return token && workspaceId ? { token, workspaceId } : null;
}

export async function saveSession(s: SessionResponse) {
  await write(TOKEN_KEY, s.token);
  await write(WORKSPACE_KEY, s.workspaceId);
}

export async function clearSession() {
  await remove(TOKEN_KEY);
  await remove(WORKSPACE_KEY);
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

/** The request never reached the server: the device is offline or the server is down.
 *  Callers treat this as the offline posture — stale reads are fine, writes are not. */
export class OfflineError extends Error {
  constructor() {
    super("The server is unreachable. Reads show the last saved data; Apply needs the server.");
  }
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const session = await savedSession();
  const headers: Record<string, string> = {
    ...((init?.headers as Record<string, string> | undefined) ?? {}),
  };
  if (init?.body) headers["Content-Type"] = "application/json";
  if (session) headers["Authorization"] = `Bearer ${session.token}`;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, { ...init, headers });
  } catch {
    throw new OfflineError();
  }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body?.error) message = body.error;
    } catch {
      /* keep the status line */
    }
    throw new ApiError(message, res.status);
  }
  return (await res.json()) as T;
}

/** A read with the offline-first posture: refresh the cache on success; on a server or
 *  network failure serve the last good payload instead of failing the screen. Auth and
 *  validation errors (4xx) are not offline conditions — they stay errors. */
async function cachedRead<T>(key: string, fetchFresh: () => Promise<T>): Promise<CachedRead<T>> {
  try {
    const data = await fetchFresh();
    await writeCache(key, data);
    return { data, stale: false, savedAt: Date.now() };
  } catch (e) {
    if (e instanceof ApiError && e.status < 500) throw e;
    const entry = await readCache(key);
    if (entry) return { data: entry.payload as T, stale: true, savedAt: entry.savedAt };
    throw e;
  }
}

export type CachedRead<T> = { data: T; stale: boolean; savedAt: number };

export const client = {
  signup(): Promise<SessionResponse> {
    return api("/v1/fixture/signup", { method: "POST" });
  },

  authStart(redirectUri: string): Promise<{ url: string; redirectUri: string }> {
    return api(`/v1/auth/start?redirect_uri=${encodeURIComponent(redirectUri)}`);
  },

  authCallback(code: string, redirectUri: string, state: string): Promise<SessionResponse> {
    return api("/v1/auth/callback", { method: "POST", body: JSON.stringify({ code, redirectUri, state }) });
  },

  addTask(task: Task): Promise<{ ok: boolean }> {
    return api("/v1/tasks", { method: "POST", body: JSON.stringify(task) });
  },

  listTasks(): Promise<Task[]> {
    return api("/v1/tasks");
  },

  listProjects(): Promise<Project[]> {
    return api("/v1/projects");
  },

  board(projectId: string): Promise<Board> {
    return api(`/v1/projects/${encodeURIComponent(projectId)}`);
  },

  addProjectTask(projectId: string, task: Task): Promise<{ ok: boolean }> {
    return api(`/v1/projects/${encodeURIComponent(projectId)}/tasks`, { method: "POST", body: JSON.stringify(task) });
  },

  reconcile(): Promise<{ ok: boolean }> {
    return api("/v1/reconcile", { method: "POST" });
  },

  plan(request: PlanningRequest, replaceExisting = false): Promise<PlanRun> {
    return api(`/v1/plan${replaceExisting ? "?replan=1" : ""}`, {
      method: "POST",
      body: JSON.stringify(request),
    });
  },

  /** Workspace-scoped persisted blocks used to build an optimistic-concurrency replan. */
  planBlocks(): Promise<PlanBlock[]> {
    return api("/v1/plan-blocks");
  },

  capacity(plan: PlanningRequest, newClientHoursPerWeek: number): Promise<CapacityResponse> {
    return api("/v1/capacity", {
      method: "POST",
      body: JSON.stringify({ v: 1, plan, newClientHoursPerWeek }),
    });
  },

  apply(runId: string): Promise<ApplyResponse> {
    return api(`/v1/plan/${encodeURIComponent(runId)}/apply`, { method: "POST" });
  },

  reject(runId: string): Promise<{ ok: boolean }> {
    return api(`/v1/plan/${encodeURIComponent(runId)}/reject`, { method: "POST" });
  },

  undo(entryId: string): Promise<{ restored: number }> {
    return api(`/v1/plan/${encodeURIComponent(entryId)}/undo`, { method: "POST" });
  },

  today(): Promise<TodayResponse> {
    return api("/v1/today");
  },

  planningSettings(): Promise<WorkSchedule> {
    return api("/v1/settings/planning");
  },

  async planningSettingsCached(): Promise<CachedPlanningSettings> {
    const s = await savedSession();
    if (!s) throw new OfflineError();
    return cachedRead(cacheKeys(s.workspaceId).planningSettings, () => this.planningSettings());
  },

  async updatePlanningSettings(schedule: WorkSchedule): Promise<WorkSchedule> {
    const data = await api<WorkSchedule>("/v1/settings/planning", { method: "PUT", body: JSON.stringify(schedule) });
    const s = await savedSession();
    if (s) await writeCache(cacheKeys(s.workspaceId).planningSettings, data);
    return data;
  },

  /** Today with the offline-first posture — the last good payload when the server is down. */
  async todayCached(): Promise<CachedToday> {
    const s = await savedSession();
    if (!s) throw new OfflineError();
    return cachedRead(cacheKeys(s.workspaceId).today, () => this.today());
  },

  portfolio(): Promise<PortfolioResponse> {
    return api("/v1/portfolio");
  },

  /** The week strip with the offline-first posture. */
  async portfolioCached(): Promise<CachedPortfolio> {
    const s = await savedSession();
    if (!s) throw new OfflineError();
    return cachedRead(cacheKeys(s.workspaceId).portfolio, () => this.portfolio());
  },

  /** The project list with the offline-first posture. */
  async listProjectsCached(): Promise<CachedProjects> {
    const s = await savedSession();
    if (!s) throw new OfflineError();
    return cachedRead(cacheKeys(s.workspaceId).projects, () => this.listProjects());
  },

  /** The workspace task set with the offline-first posture. Planning is workspace-scoped,
   * even when the board visible on screen is narrowed to one project. */
  async listTasksCached(): Promise<CachedTasks> {
    const s = await savedSession();
    if (!s) throw new OfflineError();
    return cachedRead(cacheKeys(s.workspaceId).tasks, () => this.listTasks());
  },

  /** One project's board with the offline-first posture. */
  async boardCached(projectId: string): Promise<CachedBoard> {
    const s = await savedSession();
    if (!s) throw new OfflineError();
    return cachedRead(cacheKeys(s.workspaceId).board(projectId), () => this.board(projectId));
  },

  summary(): Promise<SummaryResponse> {
    return api("/v1/summary", { method: "POST", body: "{}" });
  },

  registerDevice(token: string): Promise<{ ok: boolean }> {
    return api("/v1/devices", { method: "POST", body: JSON.stringify({ token, platform: "expo" }) });
  },

  unregisterDevice(token: string): Promise<{ ok: boolean }> {
    return api("/v1/devices", { method: "DELETE", body: JSON.stringify({ token, platform: "expo" }) });
  },
};
