"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { client, savedSession, saveSession, clearSession } from "@/lib/api";
import { WEEK_DAYS, minutesToTime, replaceWeeklyWindow, timeToMinutes, weeklyWindows } from "@/lib/working-schedule";
import type { BaselineComparison, BaselineSnapshot, BillingResponse, Board, CapacityResponse, PlanBlock, PlanRun, PlanningRequest, PortfolioResponse, Project, ScenarioResponse, SummaryResponse, Task, TodayResponse, WorkSchedule } from "@/lib/types";

// Fixture mode is for the automated journey only. Production must opt in to it explicitly;
// an omitted build variable must never silently ship a demo sign-in path.
const FIXTURE = process.env.NEXT_PUBLIC_FIXTURE === "1";
const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const WEEK_MS = 7 * DAY;

/** Monday 00:00 UTC of the current week, for the portfolio strip's bar positions. */
function weekStart(): number {
  const now = new Date();
  const daysSinceMonday = (now.getUTCDay() + 6) % 7;
  return Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()) - daysSinceMonday * DAY;
}

function fmt(ms: number): string {
  return new Date(ms).toLocaleString([], { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

/** Minutes as a person would say them: 30m, 1h, 4h 45m. */
function hm(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  const h = Math.floor(m / 60);
  if (h === 0) return `${m}m`;
  return m % 60 === 0 ? `${h}h` : `${h}h ${m % 60}m`;
}

function dayName(ms: number): string {
  return new Date(ms).toLocaleDateString([], { weekday: "short" });
}

export default function PlannerPage() {
  const session = savedSession();
  if (!session) return <SignIn />;
  return <Planner session={session} />;
}

function SignIn() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function start() {
    setBusy(true);
    setError(null);
    try {
      if (FIXTURE) {
        saveSession(await client.signup());
        window.location.reload();
      } else {
        const { url } = await client.authStart();
        window.location.assign(url);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "sign-in failed");
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <h2>Sign in</h2>
      <p className="muted">{FIXTURE ? "Fixture mode: a session without a Google account, for the automated journey." : "Connect your Google account — one consent covers identity and your calendar."}</p>
      <button className="primary" onClick={start} disabled={busy}>
        {busy ? "…" : FIXTURE ? "Start the journey" : "Sign in with Google"}
      </button>
      {error && <p className="error">{error}</p>}
    </div>
  );
}

function Planner({ session }: { session: { workspaceId: string } }) {
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [board, setBoard] = useState<Board | null>(null);
  const [newProjectName, setNewProjectName] = useState("");
  const [inboxTitle, setInboxTitle] = useState("");
  const [inboxEffort, setInboxEffort] = useState("30");
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [schedule, setSchedule] = useState<WorkSchedule | null>(null);
  const [scheduleDraft, setScheduleDraft] = useState<WorkSchedule | null>(null);
  const [scheduleMessage, setScheduleMessage] = useState<string | null>(null);
  const [planBlocks, setPlanBlocks] = useState<PlanBlock[]>([]);
  const [run, setRun] = useState<PlanRun | null>(null);
  const [entryId, setEntryId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [title, setTitle] = useState("");
  const [effort, setEffort] = useState("60");
  const [due, setDue] = useState("");
  const [capacity, setCapacity] = useState<CapacityResponse | null>(null);
  const [summary, setSummary] = useState<SummaryResponse | null>(null);
  const [waitsFor, setWaitsFor] = useState<Record<string, string>>({});
  const [scenarios, setScenarios] = useState<ScenarioResponse | null>(null);
  const [preferredOrder, setPreferredOrder] = useState<string[] | null>(null);
  const [baselines, setBaselines] = useState<BaselineSnapshot[] | null>(null);
  const [baselineId, setBaselineId] = useState<string | null>(null);
  const [variance, setVariance] = useState<BaselineComparison | null>(null);
  const [portfolio, setPortfolio] = useState<PortfolioResponse | null>(null);
  const [billing, setBilling] = useState<BillingResponse | null>(null);
  const [view, setView] = useState<"week" | "board" | "portfolio">("week");
  const plannerDays = Array.from({ length: 7 }, (_, index) => weekStart() + index * DAY);

  const refresh = useCallback(async () => {
    const [ps, td, pf, blocks, allTasks, planningSettings, bl, bi] = await Promise.all([
      client.listProjects(),
      client.today(),
      client.portfolio(),
      client.planBlocks(),
      client.listTasks(),
      client.planningSettings(),
      client.listBaselines().catch(() => null),
      client.billing().catch(() => null),
    ]);
    setProjects(ps);
    setTasks(allTasks);
    setToday(td);
    setSchedule(planningSettings);
    setScheduleDraft(planningSettings);
    setPortfolio(pf);
    setPlanBlocks(blocks);
    setBaselines(bl);
    setBilling(bi);
    setSelectedId((current) => current ?? ps.find((p) => p.isDefault)?.id ?? ps[0]?.id ?? null);
  }, []);

  async function saveSchedule() {
    if (!scheduleDraft) return;
    setBusy(true);
    setError(null);
    setScheduleMessage(null);
    try {
      const saved = await client.updatePlanningSettings(scheduleDraft);
      setSchedule(saved);
      setScheduleDraft(saved);
      setScheduleMessage("Working hours saved.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "working-hours save failed");
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    refresh().catch((e) => setError(e instanceof Error ? e.message : "load failed"));
  }, [refresh]);

  useEffect(() => {
    if (!selectedId) {
      setBoard(null);
      return;
    }
    client
      .board(selectedId)
      .then(setBoard)
      .catch((e) => setError(e instanceof Error ? e.message : "board failed"));
  }, [selectedId]);

  async function createProject() {
    const name = newProjectName.trim();
    if (!name) return;
    setBusy(true);
    setError(null);
    try {
      const created = await client.createProject(name);
      setNewProjectName("");
      const ps = await client.listProjects();
      setProjects(ps);
      setSelectedId(created.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "create failed");
    } finally {
      setBusy(false);
    }
  }

  async function addTask() {
    if (!title.trim() || !selectedId) return;
    setBusy(true);
    setError(null);
    try {
      const id = `t${crypto.randomUUID().slice(0, 8)}`;
      await client.addProjectTask(selectedId, {
        id,
        boardId: selectedId,
        columnId: null,
        title: title.trim(),
        rank: board?.tasks.length ?? 0,
        effortMinutes: Number(effort) || undefined,
        dueAt: due ? new Date(`${due}T00:00:00Z`).getTime() : undefined,
      });
      setTitle("");
      setEffort("60");
      setDue("");
      const [nextBoard, allTasks] = await Promise.all([client.board(selectedId), client.listTasks()]);
      setBoard(nextBoard);
      setTasks(allTasks);
    } catch (e) {
      setError(e instanceof Error ? e.message : "add failed");
    } finally {
      setBusy(false);
    }
  }

  async function captureInboxTask() {
    const taskTitle = inboxTitle.trim();
    if (!taskTitle) return;
    setBusy(true);
    setError(null);
    try {
      await client.addTask({
        id: `inbox-${crypto.randomUUID().slice(0, 8)}`,
        boardId: "b1",
        columnId: null,
        title: taskTitle,
        rank: 0,
        effortMinutes: Number(inboxEffort) || undefined,
      });
      setInboxTitle("");
      setInboxEffort("30");
      await refresh();
      const nextProjects = await client.listProjects();
      const defaultProject = nextProjects.find((project) => project.isDefault) ?? nextProjects[0];
      if (defaultProject) {
        setSelectedId(defaultProject.id);
        setBoard(await client.board(defaultProject.id));
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "capture failed");
    } finally {
      setBusy(false);
    }
  }

  async function connectCalendar() {
    setBusy(true);
    setError(null);
    try {
      await client.reconcile();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "reconcile failed");
    } finally {
      setBusy(false);
    }
  }

  /** The client composes the plan request from Today's events, as the journey script does:
   *  the server stays a pure planner, and the calendar's busy time is the client's reading. */
  function buildRequest(now: number, horizonDays = 7): PlanningRequest {
    return {
      v: 1,
      workspaceId: session.workspaceId,
      rangeStartMs: now - (now % DAY),
      rangeEndMs: now - (now % DAY) + horizonDays * DAY,
      nowMs: now - (now % DAY),
      items:
        tasks?.map((t, i) => ({
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
      fixedCommitments: (today?.events ?? []).map((e) => ({ startAt: e.startTime, endAt: e.endTime })),
      dependencies: Object.entries(waitsFor)
        .filter(([successorId, predecessorId]) =>
          board?.tasks.some((t) => t.id === successorId) && board?.tasks.some((t) => t.id === predecessorId)
        )
        .map(([successorId, predecessorId], i) => ({
          id: `d${i}`,
          predecessorId,
          successorId,
          type: "finish_to_start",
          lagMinutes: 0,
        })),
      scheduleIdByTaskId: {},
      preferredOrder: preferredOrder ?? [],
      schedules: schedule ? [schedule] : [],
      deadlinePolicy: "HARD",
    };
  }

  async function planMyWeek() {
    if (tasks === null || today === null || schedule === null) return;
    setBusy(true);
    setError(null);
    setRun(null);
    try {
      const nextRun = await client.plan(buildRequest(Date.now()), planBlocks.length > 0);
      setRun(nextRun);
    } catch (e) {
      setError(e instanceof Error ? e.message : "planning failed");
    } finally {
      setBusy(false);
    }
  }

  async function planMyDay() {
    if (tasks === null || today === null || schedule === null) return;
    setBusy(true);
    setError(null);
    setRun(null);
    try {
      const nextRun = await client.plan(buildRequest(Date.now(), 1), planBlocks.length > 0);
      setRun(nextRun);
    } catch (e) {
      setError(e instanceof Error ? e.message : "planning failed");
    } finally {
      setBusy(false);
    }
  }

  async function compareApproaches() {
    if (schedule === null) return;
    setBusy(true);
    setError(null);
    try {
      const res = await client.scenarios(buildRequest(Date.now()));
      setScenarios(res);
    } catch (e) {
      setError(e instanceof Error ? e.message : "scenarios failed");
    } finally {
      setBusy(false);
    }
  }

  async function takeBaseline() {
    setBusy(true);
    setError(null);
    try {
      const snapshot = await client.createBaseline();
      setBaselines(await client.listBaselines());
      setVariance(null);
      setBaselineId(snapshot.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "baseline failed");
    } finally {
      setBusy(false);
    }
  }

  async function showVariance(baselineId: string) {
    setBusy(true);
    setError(null);
    try {
      setVariance(await client.baselineVariance(baselineId));
      setBaselineId(baselineId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "variance failed");
    } finally {
      setBusy(false);
    }
  }

  async function askCapacity() {
    if (schedule === null) return;
    setBusy(true);
    setError(null);
    try {
      const res = await client.capacity({
        v: 1,
        plan: buildRequest(Date.now()),
        newClientHoursPerWeek: 8,
      });
      setCapacity(res);
    } catch (e) {
      setError(e instanceof Error ? e.message : "capacity failed");
    } finally {
      setBusy(false);
    }
  }

  async function askSummary() {
    setBusy(true);
    setError(null);
    try {
      setSummary(await client.summary());
    } catch (e) {
      setError(e instanceof Error ? e.message : "brief failed");
    } finally {
      setBusy(false);
    }
  }

  async function apply(runId: string) {
    setBusy(true);
    setError(null);
    try {
      const res = await client.apply(runId);
      setEntryId(res.entryId);
      setRun(null);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "apply failed");
    } finally {
      setBusy(false);
    }
  }

  async function reject(runId: string) {
    setBusy(true);
    setError(null);
    try {
      await client.reject(runId);
      setRun(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "reject failed");
    } finally {
      setBusy(false);
    }
  }

  async function undo() {
    if (!entryId) return;
    setBusy(true);
    setError(null);
    try {
      await client.undo(entryId);
      setEntryId(null);
      setRun(null);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "undo failed");
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    setBusy(true);
    setError(null);
    try {
      await client.logout();
    } catch (e) {
      // Clear local state even if the API is unavailable; the cookie is cleared on the
      // normal path, while a failed network call cannot leave a bearer token in storage.
      setError(e instanceof Error ? e.message : "sign-out failed");
    } finally {
      clearSession();
      window.location.reload();
    }
  }

  const inboxTasks = tasks?.filter((task) => !task.columnId) ?? [];
  const clash = today?.conflicts[0] ?? null;
  const unestimated = tasks?.filter((t) => !t.completedAt && !t.archivedAt && !t.effortMinutes).length ?? 0;
  const weekBlocks = portfolio?.rows.flatMap((row) =>
    row.weekBlocks.map((block) => ({ ...block, projectName: row.projectName })),
  ) ?? [];
  const placedMinutes = Math.round(weekBlocks.reduce((sum, b) => sum + (b.endAt - b.startAt), 0) / 60_000);

  /** Working minutes the schedule allows on a given calendar day. */
  function capacityMinutes(dayMs: number): number {
    if (!schedule) return 0;
    return weeklyWindows(schedule, new Date(dayMs).getUTCDay() + 1)
      .reduce((sum, w) => sum + Math.max(0, w.endMinute - w.startMinute), 0);
  }

  return (
    <>
      <div className="topbar">
        <span className="crumb">Plan</span>
        <span className="crumb-sep">/</span>
        <span className="crumb-current">Week of {new Date(weekStart()).toLocaleDateString([], { day: "numeric", month: "long" })}</span>
        <div className="topbar-actions">
          <Link className="btn" href="/today">Open full day</Link>
          <button className="btn btn-primary" onClick={planMyWeek} disabled={busy || schedule === null}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><path d="M5 12.5 10 17.5 19 6.5" /></svg>
            Plan my week
          </button>
        </div>
      </div>

      <div className="desk">
        <main className="view">
          <div className="view-head">
            <h1>This week</h1>
            <p className="muted">
              {today
                ? `${today.events.length} commitment${today.events.length === 1 ? "" : "s"} today · ${today.blocks.length} block${today.blocks.length === 1 ? "" : "s"} placed`
                : "Loading your calendar and plan…"}
            </p>
          </div>

          <div className="stat-row" style={{ marginTop: 16 }}>
            <div className="card stat">
              <p className="lbl">Placed this week</p>
              <p className="stat-value">
                <strong>{hm(placedMinutes)}</strong>
                <span className="stat-note">across {portfolio?.rows.length ?? 0} project{portfolio?.rows.length === 1 ? "" : "s"}</span>
              </p>
            </div>
            <div className="card stat">
              <p className="lbl">Clashes today</p>
              <p className={`stat-value${today && today.conflicts.length > 0 ? " is-alert" : ""}`}>
                <strong>{today?.conflicts.length ?? "—"}</strong>
                <span className="stat-note">{today && today.conflicts.length === 0 ? "calendar and plan agree" : "named in the rail"}</span>
              </p>
            </div>
            <div className="card stat">
              <p className="lbl">Unestimated</p>
              <p className="stat-value">
                <strong>{unestimated}</strong>
                <span className="stat-note">{unestimated > 0 ? "so every total is a floor" : "every task is sized"}</span>
              </p>
            </div>
          </div>

          {/* Analysis never writes: a proposal is shown, and Apply is the only
              thing that commits it. */}
          {run && (
            <div className="card" style={{ marginTop: 16, borderColor: "var(--outline)" }}>
              <div className="view-head">
                <h2>Proposal — {run.result.proposals.length} block{run.result.proposals.length === 1 ? "" : "s"}</h2>
              </div>
              <div style={{ marginTop: 12 }}>
                {run.result.proposals.map((p) => (
                  <div className="proposal" key={`${p.itemId}-${p.startAt}`}>
                    <strong>{p.itemId}</strong> <span className="num">{fmt(p.startAt)} → {fmt(p.endAt)}</span>
                    <div className="proposal-why">{p.reason}</div>
                  </div>
                ))}
              </div>
              {run.result.unplaced.length > 0 && (
                <p className="error" style={{ marginTop: 10, fontSize: 13 }}>
                  <strong>Not placed:</strong> {run.result.unplaced.map((u) => `${u.itemId} (${u.reason})`).join(", ")}
                </p>
              )}
              {run.result.health.warnings.length > 0 && (
                <p className="floor-note">{run.result.health.warnings.join(" · ")}</p>
              )}
              <p className="floor-note">{run.result.explanation}</p>
              <div className="clash-acts">
                <button className="btn btn-primary" onClick={() => apply(run.runId)} disabled={busy}>Apply</button>
                <button className="btn btn-quiet" onClick={() => reject(run.runId)} disabled={busy}>Discard</button>
              </div>
            </div>
          )}

          <div className="tabs" role="tablist" aria-label="Plan view">
            <button className="tab" role="tab" aria-selected={view === "week"} onClick={() => setView("week")}>Calendar</button>
            <button className="tab" role="tab" aria-selected={view === "board"} onClick={() => setView("board")}>Board</button>
            <button className="tab" role="tab" aria-selected={view === "portfolio"} onClick={() => setView("portfolio")}>Portfolio</button>
            <span className="tabs-trailing">
              {view === "week" ? "Commitments outlined, your work filled" : view === "board" ? board?.project.name ?? "Board" : "This week"}
            </span>
          </div>

          {view === "week" && (
            <div className="week" aria-label="Weekly planner" style={{ marginTop: 14 }}>
              {plannerDays.map((day) => {
                const dayEnd = day + DAY;
                const blocks = weekBlocks
                  .filter((block) => block.startAt < dayEnd && block.endAt > day)
                  .sort((a, b) => a.startAt - b.startAt);
                const events = (today?.events ?? [])
                  .filter((event) => event.startTime < dayEnd && event.endTime > day)
                  .sort((a, b) => a.startTime - b.startTime);
                const planned = Math.round(
                  blocks.reduce((sum, b) => sum + (Math.min(b.endAt, dayEnd) - Math.max(b.startAt, day)), 0) / 60_000,
                );
                const capacity = capacityMinutes(day);
                const over = capacity > 0 && planned > capacity;
                const isToday = new Date(day).toDateString() === new Date().toDateString();
                return (
                  <section
                    className={`week-day${isToday ? " is-today" : ""}${over ? " is-over" : ""}`}
                    key={day}
                  >
                    <div className="week-head">
                      <strong>{dayName(day)}</strong>
                      <span className="num">{new Date(day).getUTCDate()}</span>
                      {planned > 0 && (
                        <span className="week-head-load">
                          {hm(planned)}
                        </span>
                      )}
                    </div>
                    {events.map((event) => (
                      <div className="blk blk-fixed" key={`event-${event.id}`} title={event.title}>
                        <span className="blk-title">{event.title}</span>
                        <span className="blk-time">{clock(event.startTime)}</span>
                      </div>
                    ))}
                    {blocks.map((block) => (
                      <div className="blk blk-plan" key={`block-${block.itemId}-${block.startAt}`} title={`${block.title} · ${block.projectName}`}>
                        <span className="blk-title">{block.title}</span>
                        <span className="blk-time">{clock(block.startAt)} · {block.projectName}</span>
                      </div>
                    ))}
                    {events.length === 0 && blocks.length === 0 && (
                      <span className="week-empty">{capacity === 0 ? "Not a working day" : "Open"}</span>
                    )}
                    {over && (
                      <span className="week-empty error">
                        Over its {hm(capacity)} window
                      </span>
                    )}
                  </section>
                );
              })}
            </div>
          )}

          {view === "board" && (
            <div style={{ marginTop: 14 }}>
              {board === null ? (
                <p className="muted">loading…</p>
              ) : (
                <>
                  {board.stages.map((stage) => (
                    <section key={stage.id} style={{ marginBottom: 18 }}>
                      <div className="section-rule">
                        <span className="lbl">{stage.name}</span>
                      </div>
                      {board.tasks.filter((t) => t.columnId === stage.id).length === 0 ? (
                        <p className="muted" style={{ padding: "4px 10px" }}>Nothing here yet.</p>
                      ) : (
                        <div className="tw">
                          <table>
                            <thead>
                              <tr><th>Task</th><th className="num">Effort</th><th className="num">Due</th><th>Waits for</th></tr>
                            </thead>
                            <tbody>
                              {board.tasks.filter((t) => t.columnId === stage.id).map((t) => (
                                <tr key={t.id}>
                                  <td className="t-title">{t.title}</td>
                                  <td className={t.effortMinutes ? "num" : "num t-sub"}>{t.effortMinutes ? `${t.effortMinutes} min` : "not set"}</td>
                                  <td className={t.dueAt ? "num" : "num t-sub"}>{t.dueAt ? fmt(t.dueAt) : "—"}</td>
                                  <td>
                                    <select
                                      value={waitsFor[t.id] ?? ""}
                                      onChange={(e) =>
                                        setWaitsFor((prev) => {
                                          const next = { ...prev };
                                          if (e.target.value) next[t.id] = e.target.value;
                                          else delete next[t.id];
                                          return next;
                                        })
                                      }
                                      aria-label={`${t.title} waits for`}
                                    >
                                      <option value="">—</option>
                                      {board.tasks.filter((other) => other.id !== t.id).map((other) => (
                                        <option key={other.id} value={other.id}>{other.title}</option>
                                      ))}
                                    </select>
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </section>
                  ))}
                  <div className="row" style={{ gap: 8, padding: 0 }}>
                    <input type="text" placeholder="Task title" value={title} onChange={(e) => setTitle(e.target.value)} style={{ flex: 1, minWidth: 160 }} aria-label="Task title" />
                    <input type="number" placeholder="min" value={effort} onChange={(e) => setEffort(e.target.value)} style={{ width: 84 }} aria-label="Effort minutes" />
                    <input type="date" value={due} onChange={(e) => setDue(e.target.value)} aria-label="Due date" />
                    <button className="btn btn-primary" onClick={addTask} disabled={busy || !title.trim()}>Add</button>
                  </div>
                </>
              )}
            </div>
          )}

          {view === "portfolio" && (
            <div style={{ marginTop: 14 }}>
              {portfolio === null ? (
                <p className="muted">loading…</p>
              ) : (
                <>
                  <div className="tw">
                    <table>
                      <thead>
                        <tr><th>Project</th><th className="num">Open</th><th className="num">Done</th><th className="num">Effort</th><th className="num">Scheduled</th><th className="num">Overdue</th></tr>
                      </thead>
                      <tbody>
                        {portfolio.rows.map((r) => (
                          <tr key={r.projectId}>
                            <td className="t-title">{r.projectName}</td>
                            <td className="num">{r.openTasks}</td>
                            <td className="num t-sub">{r.doneTasks}</td>
                            <td className="num">{r.statedEffortMinutes} min</td>
                            <td className="num">{r.scheduledMinutes} min</td>
                            <td className="num" style={r.overdueTasks > 0 ? { color: "var(--deadline)", fontWeight: 500 } : undefined}>{r.overdueTasks}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  {portfolio.rows.some((r) => r.weekBlocks.length > 0) && (
                    <div style={{ marginTop: 18 }}>
                      <div className="section-rule"><span className="lbl">Where the week goes</span></div>
                      <div style={{ display: "grid", gap: 8 }}>
                        {portfolio.rows.filter((r) => r.weekBlocks.length > 0).map((r) => (
                          <div key={r.projectId} style={{ display: "flex", alignItems: "center", gap: 10 }}>
                            <span style={{ width: 110, flex: "none", fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{r.projectName}</span>
                            <div style={{ flex: 1, position: "relative", height: 14, background: "var(--surface-highest)", borderRadius: "var(--r-mark)" }}>
                              {r.weekBlocks.map((b) => {
                                const left = ((b.startAt - weekStart()) / WEEK_MS) * 100;
                                const width = ((b.endAt - b.startAt) / WEEK_MS) * 100;
                                return (
                                  <div
                                    key={`${b.itemId}-${b.startAt}`}
                                    title={b.itemId}
                                    style={{ position: "absolute", left: `${left}%`, width: `${width}%`, top: 0, bottom: 0, background: "var(--accent)", borderRadius: "var(--r-mark)" }}
                                  />
                                );
                              })}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  <p className="floor-note">{portfolio.note}</p>
                </>
              )}
            </div>
          )}

          {/* Everything a plan can do that is not the work lives behind one
              disclosure each, with its state stated in the summary. */}
          <div style={{ marginTop: 26 }}>
            <details id="settings">
              <summary>
                <span style={{ flex: 1 }}>Working hours</span>
                <span className="muted">{schedule ? `${schedule.timeZoneId} · used for every plan` : "loading…"}</span>
              </summary>
              <div className="disc-body">
                {scheduleDraft === null ? (
                  <p className="muted">loading…</p>
                ) : (
                  <>
                    <p className="muted" style={{ marginBottom: 12 }}>
                      Plans use these local wall-clock hours. Calendar events stay fixed wherever they fall.
                    </p>
                    <div className="row" style={{ padding: 0, marginBottom: 12, gap: 8 }}>
                      <label style={{ flex: 1, fontSize: 13 }}>
                        Time zone
                        <input
                          value={scheduleDraft.timeZoneId}
                          onChange={(event) => setScheduleDraft({ ...scheduleDraft, timeZoneId: event.target.value })}
                          aria-label="Working-hours timezone"
                          style={{ width: "100%", marginTop: 4 }}
                        />
                      </label>
                      <button
                        className="btn"
                        type="button"
                        onClick={() => {
                          const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
                          if (zone) setScheduleDraft({ ...scheduleDraft, timeZoneId: zone });
                        }}
                      >
                        Use this device
                      </button>
                    </div>
                    <div className="tw">
                      <table>
                        <thead><tr><th>Day</th><th className="num">Start</th><th className="num">End</th></tr></thead>
                        <tbody>
                          {WEEK_DAYS.map(({ dayOfWeek, label }) => {
                            const window = weeklyWindows(scheduleDraft, dayOfWeek)[0];
                            return (
                              <tr key={dayOfWeek}>
                                <td className="t-title">{label}</td>
                                <td className="num">
                                  <input
                                    type="text"
                                    inputMode="numeric"
                                    placeholder="09:00"
                                    value={minutesToTime(window?.startMinute)}
                                    onChange={(event) => {
                                      const currentEnd = window?.endMinute ?? 1020;
                                      setScheduleDraft(replaceWeeklyWindow(scheduleDraft, dayOfWeek, timeToMinutes(event.target.value), currentEnd));
                                    }}
                                    aria-label={`${label} start`}
                                    style={{ width: 82 }}
                                  />
                                </td>
                                <td className="num">
                                  <input
                                    type="text"
                                    inputMode="numeric"
                                    placeholder="17:00"
                                    value={minutesToTime(window?.endMinute)}
                                    onChange={(event) => {
                                      const currentStart = window?.startMinute ?? 540;
                                      setScheduleDraft(replaceWeeklyWindow(scheduleDraft, dayOfWeek, currentStart, timeToMinutes(event.target.value)));
                                    }}
                                    aria-label={`${label} end`}
                                    style={{ width: 82 }}
                                  />
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                    <div className="clash-acts">
                      <button className="btn btn-primary" onClick={saveSchedule} disabled={busy}>Save working hours</button>
                      {scheduleMessage && <span className="muted" role="status">{scheduleMessage}</span>}
                    </div>
                  </>
                )}
              </div>
            </details>

            <details id="projects">
              <summary>
                <span style={{ flex: 1 }}>Projects</span>
                <span className="muted">{projects ? `${projects.length} · ${board?.project.name ?? "none open"}` : "loading…"}</span>
              </summary>
              <div className="disc-body">
                <div className="row" style={{ padding: 0, flexWrap: "wrap", gap: 6 }}>
                  {projects?.map((p) => (
                    <button
                      key={p.id}
                      className={p.id === selectedId ? "btn btn-primary" : "btn"}
                      onClick={() => { setSelectedId(p.id); setView("board"); }}
                    >
                      {p.name}
                    </button>
                  ))}
                  <input
                    type="text"
                    placeholder="New project…"
                    value={newProjectName}
                    onChange={(e) => setNewProjectName(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") createProject(); }}
                    style={{ width: 150 }}
                    aria-label="New project name"
                  />
                  <button className="btn" onClick={createProject} disabled={busy || !newProjectName.trim()}>Create</button>
                </div>
              </div>
            </details>

            <details>
              <summary>
                <span style={{ flex: 1 }}>Compare approaches</span>
                <span className="muted">{scenarios ? scenarios.spread : "analysis only — nothing is written"}</span>
              </summary>
              <div className="disc-body">
                <button className="btn" onClick={compareApproaches} disabled={busy || schedule === null}>Compare</button>
                {scenarios && scenarios.scenarios.map((s) => (
                  <div className="row" key={s.key} style={{ padding: "10px 0", gap: 10, alignItems: "flex-start" }}>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <strong style={{ fontSize: 14 }}>{s.name}</strong>
                      <div className="muted">
                        {s.result.proposals.map((p) => p.itemId).filter((v, i, a) => a.indexOf(v) === i).length} placed
                        {s.result.unplaced.length > 0 && <> · {s.result.unplaced.length} unplaced</>}
                      </div>
                      <div className="muted">{s.rationale}</div>
                    </div>
                    <button
                      className="btn"
                      onClick={() => { setPreferredOrder(s.key === "due" ? [] : s.preferredOrder); setScenarios(null); }}
                      disabled={busy}
                    >
                      Use this
                    </button>
                  </div>
                ))}
              </div>
            </details>

            <details>
              <summary>
                <span style={{ flex: 1 }}>Baselines</span>
                <span className="muted">{baselines === null ? "loading…" : baselines.length === 0 ? "none taken" : `${baselines.length} saved`}</span>
              </summary>
              <div className="disc-body">
                <button className="btn" onClick={takeBaseline} disabled={busy}>Take a baseline now</button>
                {baselines !== null && baselines.length === 0 && (
                  <p className="muted" style={{ marginTop: 10 }}>Take one before a change you want to measure.</p>
                )}
                {baselines?.map((b) => (
                  <div className="row" key={b.id} style={{ padding: "8px 0", gap: 10 }}>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <strong style={{ fontSize: 13.5 }}>{fmt(b.createdAt)}</strong>
                      <span className="muted"> · {b.taskCount} tasks · {b.blockCount} blocks</span>
                    </div>
                    <button className="btn btn-quiet" onClick={() => showVariance(b.id)} disabled={busy}>Compare</button>
                  </div>
                ))}
                {variance && baselineId && (
                  <div style={{ marginTop: 10, paddingTop: 10, borderTop: "1px solid var(--outline-soft)" }}>
                    <p className="muted">{variance.summary}</p>
                    {variance.rows.map((row) => (
                      <div className="row" key={row.itemId} style={{ padding: "6px 0", gap: 10 }}>
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <strong style={{ fontSize: 13.5 }}>{row.title}</strong>
                          <span className="muted">
                            {" "}
                            {row.addedSinceBaseline ? "scheduled since" : row.removedSinceBaseline ? "no longer scheduled" : row.baselineStartMs ? `${fmt(row.baselineStartMs)} → ${fmt(row.currentStartMs ?? 0)}` : "—"}
                          </span>
                        </div>
                        {row.driftMinutes != null && row.driftMinutes !== 0 && (
                          <span className={row.driftMinutes > 0 ? "error" : "muted"}>
                            {row.driftMinutes > 0 ? `${Math.round(row.driftMinutes / 3600000)} h later` : `${Math.round(-row.driftMinutes / 3600000)} h earlier`}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </details>

            <details>
              <summary>
                <span style={{ flex: 1 }}>Account</span>
                <span className="muted">{billing ? `${billing.tier}${billing.status === "trial" ? " trial" : ""}` : "…"}</span>
              </summary>
              <div className="disc-body">
                {billing && (
                  <p className="muted" style={{ marginBottom: 12 }}>
                    {billing.status === "trial" && billing.trialEndsAt
                      ? `Trial ends ${fmt(billing.trialEndsAt)}. `
                      : billing.tier === "paid"
                        ? "Paid — every surface is open. "
                        : "Free tier. "}
                    {billing.usage.projects} of {billing.limits.maxProjects === 2147483647 ? "∞" : billing.limits.maxProjects} projects
                    {!billing.limits.baselines && " · baselines locked"}
                    {!billing.limits.capacity && " · capacity locked"}
                  </p>
                )}
                <div className="clash-acts">
                  {billing?.checkoutUrl && (
                    <a className="btn btn-primary" href={billing.checkoutUrl} target="_blank" rel="noreferrer">Upgrade</a>
                  )}
                  <button className="btn" onClick={connectCalendar} disabled={busy}>Connect calendar</button>
                  <button className="btn btn-quiet" onClick={signOut} disabled={busy}>Sign out</button>
                </div>
              </div>
            </details>
          </div>

          {error && <p className="error" style={{ marginTop: 16 }} role="alert">{error}</p>}
        </main>

        <aside className="aside" aria-label="Needs attention">
          <div className="card att-now">
            <div className="row" style={{ padding: 0, minHeight: 0, gap: 8 }}>
              <span className="lbl" style={{ flex: 1 }}>Today</span>
              <span className="muted num">{today ? `${hm(today.busyMinutes)} busy · ${hm(today.freeMinutes)} free` : "…"}</span>
            </div>
            <h2 style={{ marginTop: 6 }}>
              {today ? (today.blocks.length === 0 ? "Nothing placed yet" : `${today.blocks.length} block${today.blocks.length === 1 ? "" : "s"} placed`) : "Loading…"}
            </h2>
            <p className="muted" style={{ marginTop: 3 }}>
              {today ? `${today.events.length} commitment${today.events.length === 1 ? "" : "s"} the planner will not move` : ""}
            </p>
            <div className="clash-acts">
              <button className="btn" onClick={planMyDay} disabled={busy || schedule === null}>
                {today?.blocks.length ? "Replan my day" : "Plan my day"}
              </button>
              {today !== null && today.events.length === 0 && (
                <button className="btn" onClick={connectCalendar} disabled={busy}>Connect calendar</button>
              )}
              {entryId && <button className="btn btn-quiet" onClick={undo} disabled={busy}>Undo apply</button>}
            </div>
          </div>

          {clash && (
            <div className="clash att-alert">
              <p className="clash-head">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M12 8.5v5M12 17v.01M10.3 3.9 2.6 17.4A1.8 1.8 0 0 0 4.2 20h15.6a1.8 1.8 0 0 0 1.6-2.6L13.7 3.9a1.8 1.8 0 0 0-3.4 0z" /></svg>
                {today!.conflicts.length} clash{today!.conflicts.length === 1 ? "" : "es"} today
              </p>
              <p className="clash-body">
                <strong>{clash.first.title}</strong> runs into <strong>{clash.second.title}</strong> for{" "}
                <span className="num">{Math.round(clash.overlapMs / 60_000)} min</span>.
              </p>
              <div className="clash-acts">
                <Link className="btn" href="/today">See the day</Link>
                <button className="btn btn-quiet" onClick={planMyDay} disabled={busy || schedule === null}>Replan around it</button>
              </div>
            </div>
          )}

          <div className="card att-rest">
            <div className="row" style={{ padding: 0, minHeight: 0, gap: 8 }}>
              <span className="lbl" style={{ flex: 1 }}>Capacity</span>
              {capacity && (
                <span
                  className="muted"
                  style={{
                    color:
                      capacity.verdict === "CAN_TAKE" ? "var(--positive)"
                        : capacity.verdict === "CANNOT" ? "var(--deadline)"
                          : "var(--urgent)",
                    fontWeight: 600,
                  }}
                >
                  {capacity.verdict === "CAN_TAKE" ? "Fits"
                    : capacity.verdict === "MOVE" ? "Only by moving work"
                      : capacity.verdict === "INCOMPLETE" ? "Cannot say yet"
                        : "Does not fit"}
                </span>
              )}
            </div>
            {capacity ? (
              <>
                <div className="meter" role="img" aria-label={`Of ${capacity.availableMinutes} available minutes, ${capacity.plannedMinutes} planned and ${capacity.spareMinutes} spare.`}>
                  <span className="meter-plan" style={{ width: `${Math.max(0, Math.min(100, (capacity.plannedMinutes / Math.max(1, capacity.availableMinutes)) * 100))}%` }} />
                  <span className="meter-free" style={{ flex: 1 }} />
                </div>
                <div className="legend">
                  <span>Available <strong>{hm(capacity.availableMinutes)}</strong></span>
                  <span>Planned <strong>{hm(capacity.plannedMinutes)}</strong></span>
                  <span>Spare <strong>{hm(capacity.spareMinutes)}</strong></span>
                </div>
                <p className="floor-note">{capacity.sentence}</p>
              </>
            ) : (
              <>
                <p className="muted" style={{ marginTop: 6 }}>Ask whether the week has room for more.</p>
                <div className="clash-acts">
                  <button className="btn" onClick={askCapacity} disabled={busy || schedule === null}>Can I take another client?</button>
                </div>
              </>
            )}
            {unestimated > 0 && (
              <p className="floor-note">
                {unestimated} task{unestimated === 1 ? "" : "s"} state no effort, so this total is a floor.
              </p>
            )}
          </div>

          <div className="card att-rest" id="inbox">
            <div className="row" style={{ padding: 0, minHeight: 0, gap: 8 }}>
              <span className="lbl" style={{ flex: 1 }}>Inbox</span>
              <span className="muted">{tasks === null ? "…" : `${inboxTasks.length} uncategorised`}</span>
            </div>
            <div className="row" style={{ padding: 0, gap: 6, marginTop: 10 }}>
              <input
                type="text"
                placeholder="Capture a task…"
                value={inboxTitle}
                onChange={(e) => setInboxTitle(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") captureInboxTask(); }}
                aria-label="Inbox task title"
                style={{ flex: 1, minWidth: 110 }}
              />
              <input
                type="number"
                min="1"
                placeholder="min"
                value={inboxEffort}
                onChange={(e) => setInboxEffort(e.target.value)}
                aria-label="Inbox task effort minutes"
                style={{ width: 68 }}
              />
              <button className="btn btn-primary" onClick={captureInboxTask} disabled={busy || !inboxTitle.trim()}>Capture</button>
            </div>
            {inboxTasks.length > 0 && (
              <div style={{ marginTop: 12, display: "flex", flexDirection: "column", gap: 9 }}>
                {inboxTasks.slice(0, 5).map((task) => (
                  <div key={task.id} style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                    <span style={{ flex: 1, minWidth: 0, fontSize: 13 }}>{task.title}</span>
                    <span className={task.effortMinutes ? "muted num" : "num"} style={task.effortMinutes ? undefined : { fontSize: 12, color: "var(--on-faint)" }}>
                      {task.effortMinutes ? hm(task.effortMinutes) : "not set"}
                    </span>
                  </div>
                ))}
              </div>
            )}
            {tasks !== null && inboxTasks.length === 0 && <p className="floor-note">Inbox is clear.</p>}
          </div>

          <div className="card att-rest">
            <span className="lbl">Daily brief</span>
            {summary ? (
              <>
                <p style={{ fontSize: 13.5, lineHeight: "19px", marginTop: 8 }}>{summary.text}</p>
                <p className="floor-note">{summary.used} of {summary.limit} used this month{summary.used >= summary.limit && " · the monthly quota is spent"}</p>
              </>
            ) : (
              <p className="muted" style={{ marginTop: 6 }}>A written summary of the day, on request.</p>
            )}
            <div className="clash-acts">
              <button className="btn" onClick={askSummary} disabled={busy || (summary?.used ?? 0) >= (summary?.limit ?? 3)}>
                Write today&apos;s brief
              </button>
            </div>
          </div>
        </aside>
      </div>
    </>
  );
}
