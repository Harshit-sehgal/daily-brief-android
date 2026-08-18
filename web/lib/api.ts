import type {
  ApplyResponse,
  Board,
  CapacityRequest,
  CapacityResponse,
  PlanRun,
  PlanningRequest,
  Project,
  SessionResponse,
  Task,
  TodayResponse,
} from "./types";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8090";
const TOKEN_KEY = "dailybrief.token";
const WORKSPACE_KEY = "dailybrief.workspaceId";

export function savedSession(): { token: string; workspaceId: string } | null {
  if (typeof window === "undefined") return null;
  const token = window.localStorage.getItem(TOKEN_KEY);
  const workspaceId = window.localStorage.getItem(WORKSPACE_KEY);
  return token && workspaceId ? { token, workspaceId } : null;
}

export function saveSession(s: SessionResponse) {
  window.localStorage.setItem(TOKEN_KEY, s.token);
  window.localStorage.setItem(WORKSPACE_KEY, s.workspaceId);
}

export function clearSession() {
  window.localStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(WORKSPACE_KEY);
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const session = savedSession();
  const headers: Record<string, string> = { ...(init?.headers as Record<string, string> | undefined) };
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

  authStart(): Promise<{ url: string; redirectUri: string }> {
    return api(`/v1/auth/start?redirect_uri=${encodeURIComponent(window.location.origin + "/auth/callback")}`);
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

  createProject(name: string): Promise<Project> {
    return api("/v1/projects", { method: "POST", body: JSON.stringify({ v: 1, name }) });
  },

  board(projectId: string): Promise<Board> {
    return api(`/v1/projects/${encodeURIComponent(projectId)}`);
  },

  addProjectTask(projectId: string, task: Task): Promise<{ ok: boolean }> {
    return api(`/v1/projects/${encodeURIComponent(projectId)}/tasks`, {
      method: "POST",
      body: JSON.stringify(task),
    });
  },

  reconcile(): Promise<{ ok: boolean }> {
    return api("/v1/reconcile", { method: "POST" });
  },

  plan(request: PlanningRequest): Promise<PlanRun> {
    return api("/v1/plan", { method: "POST", body: JSON.stringify(request) });
  },

  capacity(request: CapacityRequest): Promise<CapacityResponse> {
    return api("/v1/capacity", { method: "POST", body: JSON.stringify(request) });
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
};