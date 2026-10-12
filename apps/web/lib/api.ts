import type {
  ApplyResponse,
  BaselineComparison,
  BaselineSnapshot,
  BrowserSessionResponse,
  BillingResponse,
  Board,
  CapacityRequest,
  CapacityResponse,
  Connections,
  DayNote,
  ExternalEvent,
  PlanRun,
  PlanningRequest,
  PortfolioResponse,
  PlanBlock,
  Project,
  ScenarioOrdering,
  ScenarioResponse,
  SummaryResponse,
  Task,
  TaskPatch,
  TodayResponse,
  WorkSchedule,
} from "./types";

const configuredApiBase = process.env.NEXT_PUBLIC_API_BASE?.trim();
if (!configuredApiBase && process.env.NODE_ENV === "production") {
  throw new Error("NEXT_PUBLIC_API_BASE must be configured for a production web build");
}
// "same-origin" means the API answers under this site's own address (/v1/…), put there
// by the proxy rule in next.config.ts or by a reverse proxy in front. One address needs
// one host name, and makes the session cookie first-party in every browser.
const API_BASE = configuredApiBase === "same-origin" ? "" : configuredApiBase ?? "http://localhost:8090";
const WORKSPACE_KEY = "dailybrief.workspaceId";
const CSRF_KEY = "dailybrief.csrf";

export function savedSession(): { workspaceId: string } | null {
  if (typeof window === "undefined") return null;
  // A pre-cookie build left a bearer token beside the workspace id. Do not keep presenting
  // that stale client state as a signed-in browser session; force a clean cookie login.
  if (window.localStorage.getItem("dailybrief.token")) {
    clearSession();
    return null;
  }
  const workspaceId = window.localStorage.getItem(WORKSPACE_KEY);
  return workspaceId ? { workspaceId } : null;
}

export function saveSession(s: BrowserSessionResponse) {
  // Remove tokens created by pre-cookie builds. The browser must never retain a bearer
  // credential that is valid for seven days in script-readable storage.
  window.localStorage.removeItem("dailybrief.token");
  window.localStorage.setItem(WORKSPACE_KEY, s.workspaceId);
  window.sessionStorage.setItem(CSRF_KEY, s.csrfToken);
}

export function clearSession() {
  window.localStorage.removeItem("dailybrief.token");
  window.localStorage.removeItem(WORKSPACE_KEY);
  window.sessionStorage.removeItem(CSRF_KEY);
}

const OAUTH_STATE_KEY = "dailybrief.oauth";

/**
 * Leave for Google's consent screen, remembering which sign-in this browser started.
 * The callback page finishes only a sign-in it can match (`claimOAuthState`): without
 * that, a link to /auth/callback carrying someone else's code would sign a reader into
 * that person's notebook, to write their own tasks where a stranger can read them.
 */
export function leaveForGoogle(url: string, returnTo: string) {
  const state = new URL(url).searchParams.get("state") ?? "";
  window.sessionStorage.setItem(OAUTH_STATE_KEY, JSON.stringify({ state, returnTo }));
  window.location.assign(url);
}

/** The page to return to when `state` is the sign-in this browser started; null when it is not. */
export function claimOAuthState(state: string): string | null {
  const raw = window.sessionStorage.getItem(OAUTH_STATE_KEY);
  window.sessionStorage.removeItem(OAUTH_STATE_KEY);
  try {
    const kept = JSON.parse(raw ?? "") as { state: string; returnTo: string };
    if (!kept.state || kept.state !== state) return null;
    return /^\/[a-z]*$/.test(kept.returnTo) ? kept.returnTo : "/";
  } catch {
    return null;
  }
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { ...(init?.headers as Record<string, string> | undefined) };
  if (init?.body) headers["Content-Type"] = "application/json";
  const method = (init?.method ?? "GET").toUpperCase();
  if (typeof window !== "undefined" && !["GET", "HEAD", "OPTIONS"].includes(method)) {
    const csrf = window.sessionStorage.getItem(CSRF_KEY);
    if (csrf) headers["X-DailyBrief-CSRF"] = csrf;
  }
  const res = await fetch(`${API_BASE}${path}`, { ...init, headers, credentials: "include" });
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

/**
 * An all-day event is a date, not an instant: Google sends "6 October", the server stores
 * it as 6 October 00:00 UTC, and in India that instant is 05:30 — so the event drew across
 * two local days. Move it onto local midnight of the same calendar date.
 */
export function localAllDay(e: ExternalEvent): ExternalEvent {
  if (!e.isAllDay) return e;
  const local = (ms: number) => {
    const d = new Date(ms);
    return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()).getTime();
  };
  return { ...e, startTime: local(e.startTime), endTime: local(e.endTime) };
}

export const client = {
  signup(): Promise<BrowserSessionResponse> {
    return api("/v1/fixture/browser-signup", { method: "POST" });
  },

  /** Sign-in asks Google only who you are; `connect` adds the calendar or Drive by its own consent. */
  authStart(connect?: "calendar" | "drive" | "plan"): Promise<{ url: string; redirectUri: string }> {
    const redirect = encodeURIComponent(window.location.origin + "/auth/callback");
    return api(`/v1/auth/start?redirect_uri=${redirect}${connect ? `&connect=${connect}` : ""}`);
  },

  authCallback(code: string, redirectUri: string, state: string): Promise<BrowserSessionResponse> {
    return api("/v1/auth/callback", { method: "POST", body: JSON.stringify({ code, redirectUri, state }) });
  },

  logout(): Promise<{ ok: boolean }> {
    return api("/v1/auth/logout", { method: "POST" });
  },

  addTask(task: Task): Promise<{ ok: boolean }> {
    return api("/v1/tasks", { method: "POST", body: JSON.stringify(task) });
  },

  patchTask(id: string, patch: TaskPatch): Promise<Task> {
    return api(`/v1/tasks/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify(patch) });
  },

  /** Calendar events overlapping a span, with all-day events moved onto the reader's own days. */
  async events(start: number, end: number): Promise<ExternalEvent[]> {
    const events = await api<ExternalEvent[]>(`/v1/events?start=${start}&end=${end}`);
    return events.map(localAllDay);
  },

  note(day: string): Promise<DayNote> {
    return api(`/v1/notes/${day}`);
  },

  saveNote(note: DayNote): Promise<{ ok: boolean }> {
    return api(`/v1/notes/${note.day}`, { method: "PUT", body: JSON.stringify(note) });
  },

  connections(): Promise<Connections> {
    return api("/v1/connections");
  },

  driveBackup(): Promise<Connections["drive"]> {
    return api("/v1/drive/backup", { method: "POST", body: "{}" });
  },

  pushPlan(): Promise<NonNullable<Connections["planCalendar"]>> {
    return api("/v1/calendar/push", { method: "POST", body: "{}" });
  },

  deleteAccount(): Promise<{ ok: boolean }> {
    return api("/v1/account", { method: "DELETE" });
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

  scenarios(plan: PlanningRequest, orderings?: ScenarioOrdering[]): Promise<ScenarioResponse> {
    return api("/v1/scenarios", {
      method: "POST",
      body: JSON.stringify(orderings ? { v: 1, plan, orderings } : { v: 1, plan }),
    });
  },

  createBaseline(): Promise<BaselineSnapshot> {
    return api("/v1/baselines", { method: "POST", body: "{}" });
  },

  listBaselines(): Promise<BaselineSnapshot[]> {
    return api("/v1/baselines");
  },

  baselineVariance(baselineId: string): Promise<BaselineComparison> {
    return api(`/v1/baselines/${encodeURIComponent(baselineId)}/variance`);
  },

  portfolio(): Promise<PortfolioResponse> {
    return api("/v1/portfolio");
  },

  planBlocks(): Promise<PlanBlock[]> {
    return api("/v1/plan-blocks");
  },

  billing(): Promise<BillingResponse> {
    return api("/v1/billing");
  },

  checkout(): Promise<{ url: string }> {
    return api("/v1/billing/checkout", { method: "POST", body: "{}" });
  },

  summary(): Promise<SummaryResponse> {
    return api("/v1/summary", { method: "POST", body: "{}" });
  },

  reconcile(): Promise<{ ok: boolean }> {
    return api("/v1/reconcile", { method: "POST" });
  },

  plan(request: PlanningRequest, replaceExisting = false): Promise<PlanRun> {
    return api(`/v1/plan${replaceExisting ? "?replan=1" : ""}`, { method: "POST", body: JSON.stringify(request) });
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

  planningSettings(): Promise<WorkSchedule> {
    return api("/v1/settings/planning");
  },

  updatePlanningSettings(schedule: WorkSchedule): Promise<WorkSchedule> {
    return api("/v1/settings/planning", { method: "PUT", body: JSON.stringify(schedule) });
  },
};
