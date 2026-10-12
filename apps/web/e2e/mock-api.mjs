// A stateful stand-in for the planning server, for looking at the web client with a
// believable week on it. The real server's fixture calendar holds two events, which
// cannot show a clash, a full day or a proposal. No dependencies; state is in memory.
//
//   node e2e/mock-api.mjs            # scenario "rich" on :8097
//   MOCK_SCENARIO=empty MOCK_PORT=8098 node e2e/mock-api.mjs
//   NEXT_PUBLIC_FIXTURE=1 NEXT_PUBLIC_API_BASE=http://localhost:8097 npx next dev -p 3007
//
// It is a design aid, not a contract test: scripts/web-journey.sh runs the real server.
import { createServer } from "node:http";

const PORT = Number(process.env.MOCK_PORT ?? 8097);
let SCENARIO = process.env.MOCK_SCENARIO ?? "rich";
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

const midnight = (ms) => {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
};
const today = midnight(Date.now());
const monday = today - ((new Date(today).getDay() + 6) % 7) * DAY;
const at = (dayOffset, hour, minute = 0) => today + dayOffset * DAY + hour * HOUR + minute * MIN;
let seq = 0;
const id = (p) => `${p}${(++seq).toString(36)}${Math.random().toString(36).slice(2, 6)}`;

const schedule = {
  id: "s1",
  name: "Working hours",
  timeZoneId: Intl.DateTimeFormat().resolvedOptions().timeZone,
  isDefault: true,
  minimumChunkMinutes: 30,
  maximumChunkMinutes: 240,
  bufferMinutes: 0,
  rank: 0,
  // Calendar numbering: Sunday = 1. A rich week works every day so today always has room.
  windows: (SCENARIO === "rich" ? [1, 2, 3, 4, 5, 6, 7] : [2, 3, 4, 5, 6]).map((d) => ({
    id: `s1-weekly-${d}-1`,
    kind: "weekly",
    dayOfWeek: d,
    localDate: null,
    startMinute: 9 * 60,
    endMinute: d === 1 || d === 7 ? 13 * 60 : 18 * 60,
    isClosed: false,
    rank: d,
  })),
};

const stages = [
  { id: "todo", name: "To do", rank: 0 },
  { id: "doing", name: "Doing", rank: 1 },
  { id: "done", name: "Done", rank: 2 },
];
let world;
function task(boardId, title, effortMinutes, extra = {}) {
  const t = { id: id("t"), boardId, columnId: boardId === "b1" ? null : "todo", title, rank: world.tasks.length, effortMinutes, priority: "normal", ...extra };
  world.tasks.push(t);
  return t;
}
function event(title, startTime, endTime, extra = {}) {
  world.events.push({ id: id("google_"), title, startTime, endTime, source: "Google Calendar", isAllDay: false, ...extra });
}
function block(t, startAt, minutes) {
  world.blocks.push({ id: id("blk"), projectId: t.boardId, taskId: t.id, startAt, endAt: startAt + minutes * MIN, position: 0, locked: false });
}

function seed() {
  seq = 0;
  world = {
  projects: [{ v: 1, id: "b1", workspaceId: "w1", name: "My Plan", isDefault: true, rank: 0 }],
  tasks: [],
  events: [],
  blocks: [],
  runs: new Map(),
  journal: new Map(),
  baselines: [],
  summaryUsed: 0,
  connections: {
    email: "maya@example.com",
    name: "Maya Rao",
    calendar: { connected: SCENARIO !== "empty", lastSyncAt: SCENARIO !== "empty" ? Date.now() - 4 * MIN : null },
    drive: { connected: false, lastBackupAt: null, fileUrl: null },
    planCalendar: { connected: false, lastPushAt: null },
  },
};
  if (SCENARIO === "rich") {
    world.projects.push(
      { v: 1, id: "p-north", workspaceId: "w1", name: "Northwind site", isDefault: false, rank: 1 },
      { v: 1, id: "p-atlas", workspaceId: "w1", name: "Atlas report", isDefault: false, rank: 2 },
    );
    event("Client stand-up", at(0, 10), at(0, 10, 30));
    event("Design review with Northwind", at(0, 14), at(0, 15));
    event("Dentist", at(0, 14, 30), at(0, 15, 15));
    // All-day events arrive as UTC midnights of their date, as the real server stores them.
    const d = new Date(today);
    const utcDate = Date.UTC(d.getFullYear(), d.getMonth(), d.getDate());
    event("Asha’s birthday", utcDate, utcDate + DAY, { isAllDay: true });
    event("Client stand-up", at(1, 10), at(1, 10, 30));
    event("Atlas steering call", at(1, 15), at(1, 16));
    event("Lunch with Priya", at(2, 12, 30), at(2, 13, 30));
    event("Quarterly planning", at(3, 9, 30), at(3, 12));
    event("Client stand-up", at(-1, 10), at(-1, 10, 30));
  
    const homepage = task("p-north", "Rework the homepage hero", 120, { dueAt: at(2, 23, 59), priority: "high" });
    const copy = task("p-north", "Write pricing page copy", 90);
    task("p-north", "Fix the mobile nav overlap", 45, { columnId: "doing" });
    task("p-north", "Set up analytics events", 60, { columnId: "done", completedAt: at(-1, 16) });
    const draft = task("p-atlas", "Draft the findings section", 180, { dueAt: at(3, 23, 59) });
    task("p-atlas", "Check the survey numbers", 60, { dueAt: at(-1, 23, 59) });
    task("p-atlas", "Collect chart sources", null);
    const invoice = task("b1", "Send the September invoice", 30, { priority: "high" });
    task("b1", "Renew the domain", 15);
    task("b1", "Book train to Pune", null);
    task("b1", "Reply to Kiran about the workshop", 30);
  
    block(homepage, at(0, 11), 120);
    block(invoice, at(0, 9), 30);
    block(copy, at(1, 11), 90);
    block(draft, at(1, 13), 120);
    block(draft, at(2, 9, 30), 60);
  } else if (SCENARIO === "journey") {
    event("Client stand-up", at(0, 14), at(0, 15), { source: "Fixture Calendar" });
    event("Focus writing", at(1, 9), at(1, 10, 30), { source: "Fixture Calendar" });
    world.connections.calendar = { connected: false, lastSyncAt: null };
    world.pendingEvents = world.events.splice(0);
  }
}
seed();

const open = (t) => !t.completedAt && !t.archivedAt;
const live = () => world.tasks.filter((t) => !t.archivedAt);
const titleOf = (taskId) => world.tasks.find((t) => t.id === taskId)?.title ?? taskId;
const overlaps = (a0, a1, b0, b1) => a0 < b1 && b0 < a1;

/** Earliest gap in the working windows that holds `minutes`, around `busy`. */
function findSlot(req, minutes, busy) {
  const sched = req.schedules[0] ?? schedule;
  for (let day = midnight(req.rangeStartMs); day < req.rangeEndMs; day += DAY) {
    const dow = new Date(day).getDay() + 1;
    for (const w of sched.windows.filter((x) => x.dayOfWeek === dow)) {
      const end = Math.min(day + w.endMinute * MIN, req.rangeEndMs);
      let start = Math.max(day + w.startMinute * MIN, req.nowMs, req.rangeStartMs);
      start = Math.ceil(start / (15 * MIN)) * 15 * MIN;
      while (start + minutes * MIN <= end) {
        const hit = busy.find((b) => overlaps(start, start + minutes * MIN, b.startAt, b.endAt));
        if (!hit) return start;
        start = Math.ceil(hit.endAt / (15 * MIN)) * 15 * MIN;
      }
    }
  }
  return null;
}

function planRun(req, replan) {
  const proposals = [];
  const unplaced = [];
  const kept = replan ? [] : req.blocks;
  const busy = [...req.fixedCommitments, ...kept.map((b) => ({ startAt: b.startAt, endAt: b.endAt }))];
  const order = [...req.items].filter(open).sort((a, b) => (a.dueAt ?? Infinity) - (b.dueAt ?? Infinity) || a.rank - b.rank);
  for (const item of order) {
    if (kept.some((b) => b.planItemId === item.id)) continue;
    if (!item.effortMinutes) {
      unplaced.push({ itemId: item.id, reason: "It has no estimate, so there is nothing to place. Give it one and plan again." });
      continue;
    }
    const start = findSlot(req, item.effortMinutes, busy);
    if (start === null) {
      unplaced.push({ itemId: item.id, reason: "There is no free time left in these working hours." });
      continue;
    }
    const p = { itemId: item.id, startAt: start, endAt: start + item.effortMinutes * MIN, reason: item.dueAt ? "Placed ahead of its due date." : "Placed in the first open time." };
    proposals.push(p);
    busy.push(p);
  }
  const runId = id("run");
  const result = {
    v: 1,
    proposals,
    unplaced,
    explanation: proposals.length ? `Fitted around ${req.fixedCommitments.length} calendar commitments, earliest due first.` : "Nothing could be placed.",
    health: { assessment: "OK", workingMinutes: 0, capacityAfterCommitmentsMinutes: 0, overloadMinutes: 0, missingEstimateCount: 0, unscheduledDemandMinutes: 0, demands: [], risks: [], repairs: [], warnings: [], explanation: "" },
  };
  world.runs.set(runId, { result, replan });
  return { runId, result };
}

function todayResponse() {
  const end = today + DAY;
  const events = world.events.filter((e) => overlaps(e.startTime, e.endTime, today, end));
  const blocks = world.blocks
    .filter((b) => overlaps(b.startAt, b.endAt, today, end))
    .map((b) => ({ id: b.id, planItemId: b.taskId, title: titleOf(b.taskId), projectName: world.projects.find((p) => p.id === b.projectId)?.name ?? null, startAt: b.startAt, endAt: b.endAt, position: 0, locked: b.locked }));
  const timed = events.filter((e) => !e.isAllDay);
  const conflicts = [];
  for (let i = 0; i < timed.length; i++)
    for (let j = i + 1; j < timed.length; j++)
      if (overlaps(timed[i].startTime, timed[i].endTime, timed[j].startTime, timed[j].endTime))
        conflicts.push({ first: timed[i], second: timed[j], overlapMs: Math.min(timed[i].endTime, timed[j].endTime) - Math.max(timed[i].startTime, timed[j].startTime) });
  const busy = Math.round(([...timed.map((e) => e.endTime - e.startTime), ...blocks.map((b) => b.endAt - b.startAt)].reduce((a, b) => a + b, 0)) / MIN);
  return { date: new Date(today).toISOString().slice(0, 10), events, blocks, conflicts, busyMinutes: busy, freeMinutes: Math.max(0, 1440 - busy) };
}

function portfolio() {
  const now = Date.now();
  const rows = world.projects.map((p) => {
    const ts = live().filter((t) => t.boardId === p.id);
    const weekBlocks = world.blocks
      .filter((b) => b.projectId === p.id && overlaps(b.startAt, b.endAt, monday, monday + 7 * DAY))
      .map((b) => ({ itemId: b.taskId, title: titleOf(b.taskId), startAt: b.startAt, endAt: b.endAt }));
    return {
      projectId: p.id,
      projectName: p.name,
      openTasks: ts.filter(open).length,
      doneTasks: ts.filter((t) => t.completedAt).length,
      statedEffortMinutes: ts.filter(open).reduce((s, t) => s + (t.effortMinutes ?? 0), 0),
      scheduledMinutes: weekBlocks.reduce((s, b) => s + (b.endAt - b.startAt) / MIN, 0),
      overdueTasks: ts.filter((t) => open(t) && t.dueAt && t.dueAt < now).length,
      unestimatedTasks: ts.filter((t) => open(t) && !t.effortMinutes).length,
      weekBlocks,
    };
  });
  const unsized = rows.reduce((s, r) => s + r.unestimatedTasks, 0);
  return { v: 1, rows, note: unsized ? `${unsized} tasks state no effort, so stated effort is a floor.` : "" };
}

function upsertTask(boardId, t) {
  const existing = world.tasks.find((x) => x.id === t.id);
  if (existing) Object.assign(existing, { title: t.title, effortMinutes: t.effortMinutes ?? null, dueAt: t.dueAt ?? null, priority: t.priority ?? existing.priority });
  else world.tasks.push({ priority: "normal", ...t, boardId, columnId: t.columnId ?? null, rank: world.tasks.length });
}

const routes = [
  ["POST", /^\/v1\/fixture\/browser-signup$/, () => ({ workspaceId: "w1", csrfToken: "mock-csrf" })],
  // A stand-in for Google: "consent" is instant and comes straight back to the web
  // app's callback, carrying which grant was asked for in the state.
  ["GET", /^\/v1\/auth\/start$/, (_, __, q) => {
    const redirectUri = q.get("redirect_uri");
    const state = `mock.${q.get("connect") ?? "signin"}.${id("s")}`;
    return { url: `${redirectUri}?code=mock-code&state=${state}`, redirectUri };
  }],
  ["POST", /^\/v1\/auth\/callback$/, (_, body) => {
    if (body.code !== "mock-code" || !String(body.state).startsWith("mock.")) return [400, { error: "bad code" }];
    const grant = body.state.split(".")[1];
    if (grant === "calendar") {
      if (world.pendingEvents) world.events.push(...world.pendingEvents.splice(0));
      world.connections.calendar = { connected: true, lastSyncAt: Date.now() };
    }
    if (grant === "plan") world.connections.planCalendar = { connected: true, lastPushAt: Date.now() };
    if (grant === "drive") world.connections.drive = { connected: true, lastBackupAt: Date.now(), fileUrl: "https://drive.google.com/file/d/mock/view" };
    return { workspaceId: "w1", csrfToken: "mock-csrf" };
  }],
  ["POST", /^\/v1\/auth\/logout$/, () => ({ ok: true })],
  // Back to the seeded week, so a test run starts from the same page every time.
  ["POST", /^\/v1\/fixture\/reset$/, (_, __, q) => {
    SCENARIO = q.get("scenario") ?? SCENARIO;
    seed();
    return { ok: true, scenario: SCENARIO };
  }],
  ["DELETE", /^\/v1\/account$/, () => (seed(), { ok: true })],
  ["GET", /^\/v1\/connections$/, () => world.connections],
  ["GET", /^\/v1\/notes\/([^/]+)$/, ([day]) => world.notes?.[day] ?? { day, text: "", strokes: [] }],
  ["PUT", /^\/v1\/notes\/([^/]+)$/, ([day], body) => ((world.notes ??= {})[day] = { ...body, day }, { ok: true })],
  ["GET", /^\/v1\/projects$/, () => world.projects],
  ["POST", /^\/v1\/projects$/, (_, body) => {
    const p = { v: 1, id: id("p-"), workspaceId: "w1", name: body.name, isDefault: false, rank: world.projects.length };
    world.projects.push(p);
    return p;
  }],
  ["GET", /^\/v1\/projects\/([^/]+)$/, ([pid]) => ({ v: 1, project: world.projects.find((p) => p.id === pid), stages, tasks: live().filter((t) => t.boardId === pid) })],
  ["POST", /^\/v1\/projects\/([^/]+)\/tasks$/, ([pid], body) => (upsertTask(pid, body), { ok: true })],
  ["GET", /^\/v1\/tasks$/, () => live()],
  ["POST", /^\/v1\/tasks$/, (_, body) => (upsertTask(body.boardId ?? "b1", body), { ok: true })],
  ["PATCH", /^\/v1\/tasks\/([^/]+)$/, ([tid], body) => {
    const t = world.tasks.find((x) => x.id === tid);
    if (!t) return [404, { error: "no such task" }];
    for (const k of ["title", "effortMinutes", "dueAt", "priority"]) if (k in body) t[k] = body[k];
    if ("completed" in body) t.completedAt = body.completed ? Date.now() : null;
    if ("archived" in body) t.archivedAt = body.archived ? Date.now() : null;
    // Finished or rubbed-out work leaves the plan; what already happened stays as history.
    if (body.completed || body.archived) world.blocks = world.blocks.filter((b) => b.taskId !== tid || b.startAt < Date.now());
    return t;
  }],
  ["GET", /^\/v1\/events$/, (_, __, q) => {
    const start = Number(q.get("start") ?? 0);
    const end = Number(q.get("end") ?? Number.MAX_SAFE_INTEGER);
    return world.events.filter((e) => overlaps(e.startTime, e.endTime, start, end));
  }],
  ["GET", /^\/v1\/today$/, todayResponse],
  ["GET", /^\/v1\/portfolio$/, portfolio],
  ["GET", /^\/v1\/plan-blocks$/, () => world.blocks],
  ["GET", /^\/v1\/settings\/planning$/, () => schedule],
  ["PUT", /^\/v1\/settings\/planning$/, (_, body) => Object.assign(schedule, body)],
  ["GET", /^\/v1\/baselines$/, () => world.baselines],
  ["POST", /^\/v1\/baselines$/, () => {
    const b = { v: 1, id: id("base"), workspaceId: "w1", createdAt: Date.now(), taskCount: live().length, blockCount: world.blocks.length };
    world.baselines.unshift(b);
    return b;
  }],
  ["GET", /^\/v1\/baselines\/([^/]+)\/variance$/, () => ({ v: 1, summary: "Nothing has moved since this baseline.", rows: [] })],
  ["GET", /^\/v1\/billing$/, () => ({ v: 1, tier: "paid", status: "active", limits: { maxProjects: 2147483647, baselines: true, capacity: true }, usage: { projects: world.projects.length, baselines: world.baselines.length } })],
  ["POST", /^\/v1\/summary$/, () => ({ v: 1, date: "", text: "A full afternoon: the design review runs into the dentist at 14:30, so one of them has to move. The morning is yours for the homepage hero.", source: "fixture", used: ++world.summaryUsed, limit: 30 })],
  ["POST", /^\/v1\/reconcile$/, () => {
    if (world.pendingEvents) world.events.push(...world.pendingEvents.splice(0));
    world.connections.calendar = { connected: true, lastSyncAt: Date.now() };
    return { ok: true };
  }],
  ["POST", /^\/v1\/calendar\/push$/, () => (world.connections.planCalendar = { connected: true, lastPushAt: Date.now() })],
  ["POST", /^\/v1\/drive\/backup$/, () => {
    world.connections.drive = { connected: true, lastBackupAt: Date.now(), fileUrl: "https://drive.google.com/file/d/mock/view" };
    return world.connections.drive;
  }],
  ["POST", /^\/v1\/plan$/, (_, body, q) => planRun(body, q.get("replan") === "1")],
  ["POST", /^\/v1\/plan\/([^/]+)\/apply$/, ([runId]) => {
    const run = world.runs.get(runId);
    if (!run) return [404, { error: "no such run" }];
    const entryId = id("entry");
    world.journal.set(entryId, world.blocks);
    const placed = new Set(run.result.proposals.map((p) => p.itemId));
    world.blocks = [
      ...world.blocks.filter((b) => (run.replan ? false : !placed.has(b.taskId))),
      ...run.result.proposals.map((p) => ({ id: id("blk"), projectId: world.tasks.find((t) => t.id === p.itemId)?.boardId ?? "b1", taskId: p.itemId, startAt: p.startAt, endAt: p.endAt, position: 0, locked: false })),
    ];
    world.runs.delete(runId);
    return { blocks: run.result.proposals.length, entryId };
  }],
  ["POST", /^\/v1\/plan\/([^/]+)\/reject$/, ([runId]) => (world.runs.delete(runId), { ok: true })],
  ["POST", /^\/v1\/plan\/([^/]+)\/undo$/, ([entryId]) => {
    const before = world.journal.get(entryId);
    if (!before) return [409, { error: "that change can no longer be undone" }];
    world.blocks = before;
    world.journal.delete(entryId);
    return { restored: before.length };
  }],
  ["POST", /^\/v1\/scenarios$/, (_, body) => ({
    v: 1,
    spread: "All three orders place the same work; they differ in what goes first.",
    scenarios: [
      ["due", "Earliest due first", "Protects deadlines."],
      ["short", "Shortest first", "Clears small things early."],
      ["rank", "In the order you ranked", "Follows your own list."],
    ].map(([key, name, rationale]) => ({ key, name, rationale, preferredOrder: body.plan.items.map((i) => i.id), result: planRun(body.plan, false).result })),
  })],
  ["POST", /^\/v1\/capacity$/, () => ({ v: 1, availableMinutes: 2400, plannedMinutes: 1260, spareMinutes: 1140, verdict: "CAN_TAKE", sentence: "Another 8 hours a week fits with 11 hours to spare.", moves: [] })],
];

createServer(async (req, res) => {
  const origin = req.headers.origin ?? "*";
  res.setHeader("Access-Control-Allow-Origin", origin);
  res.setHeader("Access-Control-Allow-Credentials", "true");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, X-DailyBrief-CSRF");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
  if (req.method === "OPTIONS") return res.writeHead(204).end();
  const url = new URL(req.url, "http://mock");
  let raw = "";
  for await (const chunk of req) raw += chunk;
  const body = raw ? JSON.parse(raw) : {};
  // Saving a new task takes a moment, as it does over a real network. Without this the
  // mock answered before a test could act, and hid a race the real server showed: an
  // edit to a just-jotted task arriving before the task itself.
  if (req.method === "POST" && /\/tasks$/.test(url.pathname)) await new Promise((done) => setTimeout(done, 250));
  for (const [method, pattern, handle] of routes) {
    const m = req.method === method && url.pathname.match(pattern);
    if (!m) continue;
    const out = handle(m.slice(1).map(decodeURIComponent), body, url.searchParams);
    const [status, payload] = Array.isArray(out) && typeof out[0] === "number" ? out : [200, out];
    res.writeHead(status, { "Content-Type": "application/json" });
    return res.end(JSON.stringify(payload));
  }
  res.writeHead(404, { "Content-Type": "application/json" }).end(JSON.stringify({ error: `mock has no ${req.method} ${url.pathname}` }));
}).listen(PORT, () => console.log(`mock api (${SCENARIO}) on :${PORT}`));
