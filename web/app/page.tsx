"use client";

import { useCallback, useEffect, useState } from "react";
import { client, savedSession, saveSession, clearSession, ApiError } from "@/lib/api";
import type { BaselineComparison, BaselineSnapshot, BillingResponse, Board, CapacityResponse, PlanRun, PlanningRequest, PortfolioResponse, Project, ScenarioResponse, SummaryResponse, Task, TodayResponse } from "@/lib/types";

const FIXTURE = (process.env.NEXT_PUBLIC_FIXTURE ?? "1") === "1";
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

/** Mon–Fri 09:00–17:00 UTC, the slice's default working week. */
function weekWindows() {
  return [1, 2, 3, 4, 5].map((d) => ({ id: `w${d}`, dayOfWeek: d, startMinute: 540, endMinute: 1020, rank: d }));
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

function Planner({ session }: { session: { token: string; workspaceId: string } }) {
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [board, setBoard] = useState<Board | null>(null);
  const [newProjectName, setNewProjectName] = useState("");
  const [today, setToday] = useState<TodayResponse | null>(null);
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

  const refresh = useCallback(async () => {
    const [ps, td, pf, bl, bi] = await Promise.all([
      client.listProjects(),
      client.today(),
      client.portfolio(),
      client.listBaselines().catch(() => null),
      client.billing().catch(() => null),
    ]);
    setProjects(ps);
    setToday(td);
    setPortfolio(pf);
    setBaselines(bl);
    setBilling(bi);
    setSelectedId((current) => current ?? ps.find((p) => p.isDefault)?.id ?? ps[0]?.id ?? null);
  }, []);

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
      setBoard(await client.board(selectedId));
    } catch (e) {
      setError(e instanceof Error ? e.message : "add failed");
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
  function buildRequest(now: number): PlanningRequest {
    return {
      v: 1,
      workspaceId: session.workspaceId,
      rangeStartMs: now - (now % DAY),
      rangeEndMs: now - (now % DAY) + 7 * DAY,
      nowMs: now - (now % DAY),
      items:
        board?.tasks.map((t, i) => ({
          ...t,
          boardId: t.boardId ?? selectedId ?? "b1",
          rank: t.rank ?? i,
          effortMinutes: t.effortMinutes ?? undefined,
        })) ?? [],
      blocks: [],
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
      schedules: [
        {
          id: "s1",
          name: "Weekdays",
          timeZoneId: "UTC",
          isDefault: true,
          minimumChunkMinutes: 30,
          maximumChunkMinutes: 120,
          bufferMinutes: 0,
          rank: 0,
          windows: weekWindows(),
        },
      ],
      deadlinePolicy: "HARD",
    };
  }

  async function planMyWeek() {
    setBusy(true);
    setError(null);
    setRun(null);
    try {
      const nextRun = await client.plan(buildRequest(Date.now()));
      setRun(nextRun);
    } catch (e) {
      setError(e instanceof Error ? e.message : "planning failed");
    } finally {
      setBusy(false);
    }
  }

  async function compareApproaches() {
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

  return (
    <>
      <div className="card">
        <div className="row">
          <h2 style={{ margin: 0 }}>Projects</h2>
          <span style={{ flex: 1 }} />
          <button onClick={() => { clearSession(); window.location.reload(); }}>Sign out</button>
        </div>
        {projects === null ? (
          <p className="muted">loading…</p>
        ) : (
          <div className="row" style={{ flexWrap: "wrap", gap: 6 }}>
            {projects.map((p) => (
              <button
                key={p.id}
                className={p.id === selectedId ? "primary" : ""}
                onClick={() => setSelectedId(p.id)}
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
              style={{ width: 140 }}
              aria-label="New project name"
            />
            <button onClick={createProject} disabled={busy || !newProjectName.trim()}>Create</button>
          </div>
        )}
      </div>

      <div className="card">
        <div className="row">
          <h2 style={{ margin: 0 }}>{board ? board.project.name : "Board"}</h2>
          <span className="tag">{board?.tasks.length ?? "…"} tasks</span>
        </div>
        {board === null ? (
          <p className="muted">loading…</p>
        ) : (
          <>
            {board.stages.map((stage) => (
              <div key={stage.id}>
                <strong>{stage.name}</strong>
                {board.tasks.filter((t) => t.columnId === stage.id).length === 0 ? (
                  <p className="muted" style={{ margin: "2px 0 8px" }}>—</p>
                ) : (
                  <table>
                    <thead>
                      <tr><th>Task</th><th>Effort</th><th>Due</th><th>Waits for</th></tr>
                    </thead>
                    <tbody>
                      {board.tasks.filter((t) => t.columnId === stage.id).map((t) => (
                        <tr key={t.id}>
                          <td>{t.title}</td>
                          <td>{t.effortMinutes ? `${t.effortMinutes} min` : "—"}</td>
                          <td>{t.dueAt ? fmt(t.dueAt) : "—"}</td>
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
                )}
              </div>
            ))}
            <div className="row" style={{ marginTop: 12 }}>
              <input type="text" placeholder="Task title" value={title} onChange={(e) => setTitle(e.target.value)} style={{ flex: 1 }} />
              <input type="number" placeholder="min" value={effort} onChange={(e) => setEffort(e.target.value)} style={{ width: 80 }} aria-label="Effort minutes" />
              <input type="date" value={due} onChange={(e) => setDue(e.target.value)} aria-label="Due date" />
              <button className="primary" onClick={addTask} disabled={busy || !title.trim()}>Add</button>
            </div>
          </>
        )}
      </div>

      <div className="card">
        <div className="row">
          <h2 style={{ margin: 0 }}>Calendar</h2>
          <button onClick={connectCalendar} disabled={busy}>Connect calendar</button>
        </div>
        {today && (
          <p className="muted">
            {today.events.length} event{today.events.length === 1 ? "" : "s"} today · {today.busyMinutes} busy minutes ·{" "}
            {today.freeMinutes} free
            {today.conflicts.length > 0 && <> · <span className="error">{today.conflicts.length} overlap{today.conflicts.length === 1 ? "" : "s"}</span></>}
          </p>
        )}
        <div className="row" style={{ marginTop: 8 }}>
          <button className="primary" onClick={planMyWeek} disabled={busy}>Plan my week</button>
          <button onClick={askCapacity} disabled={busy}>Can I take another client?</button>
          {entryId && <button onClick={undo} disabled={busy}>Undo apply</button>}
        </div>
        {capacity && (
          <p className={capacity.verdict === "CANNOT" || capacity.verdict === "INCOMPLETE" ? "error" : "muted"} style={{ marginTop: 8 }}>
            <strong>
              {capacity.availableMinutes / 60} h available · {capacity.plannedMinutes / 60} h planned ·{" "}
              {capacity.spareMinutes / 60} h spare
            </strong>{" "}
            — {capacity.sentence}
            {capacity.moves.length > 0 && (
              <> Moving: {capacity.moves.map((m) => `"${m.title}"`).join(", ")}.</>
            )}
          </p>
        )}
        {summary && (
          <div style={{ marginTop: 10, borderTop: "1px solid var(--hairline)", paddingTop: 10 }}>
            <p style={{ margin: 0 }}>{summary.text}</p>
            <p className="muted" style={{ marginTop: 6 }}>
              Daily brief · {summary.used} of {summary.limit} used this month
              {summary.used >= summary.limit && " · the monthly quota is spent"}
            </p>
          </div>
        )}
        <div className="row" style={{ marginTop: 8 }}>
          <button onClick={askSummary} disabled={busy || (summary?.used ?? 0) >= (summary?.limit ?? 3)}>
            Write today's brief
          </button>
        </div>
      </div>

      <div className="card">
        <div className="row">
          <h2 style={{ margin: 0 }}>Compare approaches</h2>
          <button onClick={compareApproaches} disabled={busy}>Compare</button>
        </div>
        {scenarios && (
          <>
            <p className="muted">{scenarios.spread}</p>
            {scenarios.scenarios.map((s) => (
              <div className="row" key={s.key} style={{ marginTop: 6 }}>
                <div style={{ flex: 1 }}>
                  <strong>{s.name}</strong>
                  <div className="muted">
                    {s.result.proposals.map((p) => p.itemId).filter((v, i, a) => a.indexOf(v) === i).length} placed
                    {s.result.unplaced.length > 0 && <> · {s.result.unplaced.length} unplaced</>}
                  </div>
                  <div className="muted">{s.rationale}</div>
                </div>
                <button
                  onClick={() => { setPreferredOrder(s.key === "due" ? [] : s.preferredOrder); setScenarios(null); }}
                  disabled={busy}
                >
                  Use this
                </button>
              </div>
            ))}
          </>
        )}
      </div>

      <div className="card">
        <div className="row">
          <h2 style={{ margin: 0 }}>Portfolio</h2>
          <span className="tag">this week</span>
        </div>
        {portfolio && (
          <>
            <table>
              <thead>
                <tr><th>Project</th><th>Open</th><th>Done</th><th>Effort</th><th>Scheduled</th><th>Overdue</th></tr>
              </thead>
              <tbody>
                {portfolio.rows.map((r) => (
                  <tr key={r.projectId}>
                    <td>{r.projectName}</td>
                    <td>{r.openTasks}</td>
                    <td>{r.doneTasks}</td>
                    <td>{r.statedEffortMinutes} min</td>
                    <td>{r.scheduledMinutes} min</td>
                    <td>{r.overdueTasks}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {portfolio.rows.some((r) => r.weekBlocks.length > 0) && (
              <div style={{ marginTop: 8, display: "grid", gap: 4 }}>
                {portfolio.rows.filter((r) => r.weekBlocks.length > 0).map((r) => (
                  <div key={r.projectId} className="row" style={{ gap: 6 }}>
                    <span style={{ width: 90, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{r.projectName}</span>
                    <div style={{ flex: 1, position: "relative", height: 14, background: "rgba(0,0,0,0.06)" }}>
                      {r.weekBlocks.map((b) => {
                        const left = ((b.startAt - weekStart()) / WEEK_MS) * 100;
                        const width = ((b.endAt - b.startAt) / WEEK_MS) * 100;
                        return (
                          <div
                            key={`${b.itemId}-${b.startAt}`}
                            title={b.itemId}
                            style={{ position: "absolute", left: `${left}%`, width: `${width}%`, top: 0, bottom: 0, background: "#7c5cff" }}
                          />
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            )}
            <p className="muted" style={{ marginTop: 8 }}>{portfolio.note}</p>
          </>
        )}
      </div>

      <div className="card">
        <div className="row">
          <h2 style={{ margin: 0 }}>Baselines</h2>
          <button onClick={takeBaseline} disabled={busy}>Take baseline</button>
        </div>
        {baselines === null ? (
          <p className="muted">loading…</p>
        ) : baselines.length === 0 ? (
          <p className="muted">No baselines yet — take one before a change you want to measure.</p>
        ) : (
          <>
            {baselines.map((b) => (
              <div className="row" key={b.id} style={{ marginTop: 6 }}>
                <div style={{ flex: 1 }}>
                  <strong>{fmt(b.createdAt)}</strong>
                  <span className="muted"> · {b.taskCount} tasks · {b.blockCount} blocks</span>
                </div>
                <button onClick={() => showVariance(b.id)} disabled={busy}>Variance</button>
              </div>
            ))}
            {variance && baselineId && (
              <div style={{ marginTop: 8 }}>
                <p className="muted">{variance.summary}</p>
                {variance.rows.map((row) => (
                  <div className="row" key={row.itemId} style={{ marginTop: 4 }}>
                    <div style={{ flex: 1 }}>
                      <strong>{row.title}</strong>
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
          </>
        )}
      </div>

      <div className="card">
        <div className="row">
          <h2 style={{ margin: 0 }}>Plan</h2>
          <span className="tag">{billing ? `${billing.tier}${billing.status === "trial" ? " trial" : ""}` : "…"}</span>
        </div>
        {billing && (
          <>
            <p className="muted">
              {billing.status === "trial" && billing.trialEndsAt
                ? `Trial ends ${fmt(billing.trialEndsAt)}. `
                : billing.tier === "paid"
                  ? "Paid — every surface is open. "
                  : "Free tier. "}
              {billing.usage.projects} of {billing.limits.maxProjects === 2147483647 ? "∞" : billing.limits.maxProjects} projects
              {!billing.limits.baselines && " · baselines locked"}
              {!billing.limits.capacity && " · capacity locked"}
            </p>
            {billing.checkoutUrl ? (
              <a className="button primary" href={billing.checkoutUrl} target="_blank" rel="noreferrer">Upgrade</a>
            ) : (
              <p className="muted">Billing is not configured on this deployment.</p>
            )}
          </>
        )}
      </div>

      {run && (
        <div className="card">
          <h2>Proposal — {run.result.proposals.length} block{run.result.proposals.length === 1 ? "" : "s"}</h2>
          {run.result.proposals.map((p) => (
            <div className="proposal" key={`${p.itemId}-${p.startAt}`}>
              <strong>{p.itemId}</strong> · {fmt(p.startAt)} → {fmt(p.endAt)}
              <div className="muted">{p.reason}</div>
            </div>
          ))}
          {run.result.unplaced.length > 0 && (
            <p className="error">Could not place: {run.result.unplaced.map((u) => `${u.itemId} (${u.reason})`).join(", ")}</p>
          )}
          {run.result.health.warnings.length > 0 && (
            <p className="muted">Health: {run.result.health.warnings.join(" · ")}</p>
          )}
          <p className="muted">{run.result.explanation}</p>
          <div className="row">
            <button className="primary" onClick={() => apply(run.runId)} disabled={busy}>Apply</button>
            <button onClick={() => reject(run.runId)} disabled={busy}>Reject</button>
          </div>
        </div>
      )}

      {error && <p className="error">{error}</p>}
    </>
  );
}