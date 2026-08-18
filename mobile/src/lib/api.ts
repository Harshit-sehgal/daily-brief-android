import { Platform } from "react-native";
import * as SecureStore from "expo-secure-store";

import type {
  ApplyResponse,
  Board,
  PlanRun,
  PlanningRequest,
  PortfolioResponse,
  Project,
  SessionResponse,
  SummaryResponse,
  Task,
  TodayResponse,
} from "./types";

/** Android emulators reach the host machine at 10.0.2.2; simulators and web use localhost.
 *  Override with EXPO_PUBLIC_API_BASE for a real device or a deployed server. */
export const API_BASE =
  process.env.EXPO_PUBLIC_API_BASE ??
  (Platform.OS === "android" ? "http://10.0.2.2:8090" : "http://localhost:8090");

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

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const session = await savedSession();
  const headers: Record<string, string> = {
    ...((init?.headers as Record<string, string> | undefined) ?? {}),
  };
  if (init?.body) headers["Content-Type"] = "application/json";
  if (session) headers["Authorization"] = `Bearer ${session.token}`;
  const res = await fetch(`${API_BASE}${path}`, { ...init, headers });
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

export const client = {
  signup(): Promise<SessionResponse> {
    return api("/v1/fixture/signup", { method: "POST" });
  },

  authStart(redirectUri: string): Promise<{ url: string; redirectUri: string }> {
    return api(`/v1/auth/start?redirect_uri=${encodeURIComponent(redirectUri)}`);
  },

  authCallback(code: string, redirectUri: string): Promise<SessionResponse> {
    return api("/v1/auth/callback", { method: "POST", body: JSON.stringify({ code, redirectUri }) });
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
    return api(`/v1/projects/${projectId}`);
  },

  addProjectTask(projectId: string, task: Task): Promise<{ ok: boolean }> {
    return api(`/v1/projects/${projectId}/tasks`, { method: "POST", body: JSON.stringify(task) });
  },

  reconcile(): Promise<{ ok: boolean }> {
    return api("/v1/reconcile", { method: "POST" });
  },

  plan(request: PlanningRequest): Promise<PlanRun> {
    return api("/v1/plan", { method: "POST", body: JSON.stringify(request) });
  },

  apply(runId: string): Promise<ApplyResponse> {
    return api(`/v1/plan/${runId}/apply`, { method: "POST" });
  },

  reject(runId: string): Promise<{ ok: boolean }> {
    return api(`/v1/plan/${runId}/reject`, { method: "POST" });
  },

  undo(entryId: string): Promise<{ restored: number }> {
    return api(`/v1/plan/${entryId}/undo`, { method: "POST" });
  },

  today(): Promise<TodayResponse> {
    return api("/v1/today");
  },

  summary(): Promise<SummaryResponse> {
    return api("/v1/summary", { method: "POST", body: "{}" });
  },

  portfolio(): Promise<PortfolioResponse> {
    return api("/v1/portfolio");
  },
};