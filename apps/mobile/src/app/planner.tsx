import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import {
  ActivityIndicator,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import { MinimumTouchTarget, Palette, Radius, Space } from "@/constants/palette";
import { ApiError, client, savedSession } from "@/lib/api";
import { removeReplaceableBlocks, toPlanningItems, toScheduledBlocks } from "@/lib/planner-contract";
import type {
  Board,
  CapacityResponse,
  PlanBlock,
  PlanRun,
  PlanningRequest,
  PortfolioResponse,
  Project,
  Task,
  TodayResponse,
  WorkSchedule,
} from "@/lib/types";

const DAY = 86_400_000;
const WEEK_MS = 7 * DAY;

/** Monday 00:00 UTC of the current week, for the portfolio strip's bar positions. */
function weekStart(): number {
  const now = new Date();
  const daysSinceMonday = (now.getUTCDay() + 6) % 7;
  return Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()) - daysSinceMonday * DAY;
}

/** The native engine bridge runs only where a native module exists; on web the server
 *  is the only planner. Lazy require so web bundling never touches the native module. */
function engineBridge(): { previewPlan(requestJson: string): Promise<string> } | null {
  if (Platform.OS === "web") return null;
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const mod = require("expo-planner-engine").default;
    return typeof mod?.previewPlan === "function" ? mod : null;
  } catch {
    return null;
  }
}

/** The server keys tasks by any unique id; a time+random suffix is enough for a demo task. */
function newTaskId(): string {
  return `t-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export default function PlannerScreen() {
  const router = useRouter();
  const [session, setSession] = useState<{ token: string; workspaceId: string } | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [schedule, setSchedule] = useState<WorkSchedule | null>(null);
  const [board, setBoard] = useState<Board | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [run, setRun] = useState<PlanRun | null>(null);
  const [isPreview, setIsPreview] = useState(false);
  const [entryId, setEntryId] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [portfolio, setPortfolio] = useState<PortfolioResponse | null>(null);
  const [planBlocks, setPlanBlocks] = useState<PlanBlock[]>([]);
  const [planBlocksFresh, setPlanBlocksFresh] = useState(false);
  const [stale, setStale] = useState(false);
  const [capacityHours, setCapacityHours] = useState("8");
  const [capacity, setCapacity] = useState<CapacityResponse | null>(null);
  const [capacityBusy, setCapacityBusy] = useState(false);

  const loadBoard = useCallback(async (projectId: string) => {
    const cached = await client.boardCached(projectId);
    setBoard(cached.data);
    return cached.stale;
  }, []);

  const refresh = useCallback(async () => {
    const s = await savedSession();
    if (!s) {
      router.replace("/");
      return;
    }
    setSession(s);
    try {
      const [ps, workspaceTasks, planningSettings] = await Promise.all([
        client.listProjectsCached(),
        client.listTasksCached(),
        client.planningSettingsCached(),
      ]);
      setProjects(ps.data);
      setTasks(workspaceTasks.data);
      setSchedule(planningSettings.data);
      const first = ps.data.find((p) => p.isDefault)?.id ?? ps.data[0]?.id ?? null;
      setSelectedId((current) => (current && ps.data.some((p) => p.id === current) ? current : first));
      let staleNow = ps.stale || workspaceTasks.stale || planningSettings.stale;
      if (first) staleNow = (await loadBoard(first)) || staleNow;
      try {
        setPlanBlocks(await client.planBlocks());
        setPlanBlocksFresh(true);
      } catch (e) {
        // A missing/stale block snapshot must never be used for a server Apply. Keep
        // the last in-memory snapshot for local preview, but mark it non-authoritative.
        if (e instanceof ApiError && e.status < 500) throw e;
        setPlanBlocksFresh(false);
        staleNow = true;
      }
      // The strip is a view, not a control: a portfolio failure must not blank the board.
      try {
        const portfolio = await client.portfolioCached();
        setPortfolio(portfolio.data);
        staleNow = portfolio.stale || staleNow;
      } catch {
        setPortfolio(null);
      }
      setStale(staleNow);
    } catch (e) {
      setError(e instanceof Error ? e.message : "load failed");
    }
  }, [router, loadBoard]);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  async function addTask() {
    if (!title.trim() || !selectedId) return;
    setBusy(true);
    setError(null);
    try {
      await client.addProjectTask(selectedId, {
        id: newTaskId(),
        boardId: selectedId,
        columnId: board?.stages[0]?.id ?? null,
        title: title.trim(),
        rank: (board?.tasks.length ?? 0) + 1,
      });
      setTitle("");
      await loadBoard(selectedId);
      try {
        const workspaceTasks = await client.listTasksCached();
        setTasks(workspaceTasks.data);
        setStale((current) => current || workspaceTasks.stale);
      } catch {
        // The board refresh above is enough to show the newly-created task; the next
        // successful workspace read will restore the planning set.
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "add failed");
    } finally {
      setBusy(false);
    }
  }

  async function selectProject(id: string) {
    setSelectedId(id);
    setRun(null);
    setError(null);
    try {
      const staleBoard = await loadBoard(id);
      setStale(staleBoard || !planBlocksFresh);
    } catch (e) {
      setError(e instanceof Error ? e.message : "load failed");
    }
  }

  function planRequest(tasks: Task[], now: number, blocks = planBlocks): PlanningRequest {
    const rangeStart = now - (now % DAY);
    return {
      v: 1,
      workspaceId: session!.workspaceId,
      rangeStartMs: rangeStart,
      rangeEndMs: rangeStart + 7 * DAY,
      nowMs: rangeStart,
      items: toPlanningItems(tasks),
      blocks: toScheduledBlocks(blocks),
      fixedCommitments: [],
      dependencies: [],
      scheduleIdByTaskId: {},
      preferredOrder: [],
      schedules: schedule ? [schedule] : [],
      deadlinePolicy: "HARD",
    };
  }

  /** The server removes replaceable target blocks before planning. Mirror that behavior
   *  for a native preview so an offline preview does not promise a different schedule. */
  function nativePlanRequest(request: PlanningRequest, replaceExisting: boolean): PlanningRequest {
    if (!replaceExisting) return request;
    const targetIds = new Set(request.items.map((item) => item.id));
    return {
      ...request,
      blocks: removeReplaceableBlocks(request.blocks, targetIds),
    };
  }

  async function refreshPlanBlocks(): Promise<{ blocks: PlanBlock[]; fresh: boolean }> {
    try {
      const blocks = await client.planBlocks();
      setPlanBlocks(blocks);
      setPlanBlocksFresh(true);
      return { blocks, fresh: true };
    } catch (e) {
      if (e instanceof ApiError && e.status < 500) throw e;
      setPlanBlocksFresh(false);
      setStale(true);
      return { blocks: planBlocks, fresh: false };
    }
  }

  /** The client composes fixedCommitments from Today's events, as the web client does:
   *  the server stays a pure planner, and the calendar's busy time is the client's reading.
   *  When the server is unreachable, the same computation runs locally through the native
   *  bridge (same Mapping, same engine) — preview only: Apply needs the server. */
  async function planMyWeek() {
    if (!session || !board || tasks === null || schedule === null) return;
    setBusy(true);
    setError(null);
    setRun(null);
    setIsPreview(false);
    try {
      let today: TodayResponse;
      try {
        const cached = await client.todayCached();
        today = cached.data;
      } catch {
        today = { date: "", events: [], blocks: [], conflicts: [], busyMinutes: 0, freeMinutes: 0 };
      }
      const blockSnapshot = await refreshPlanBlocks();
      const now = Date.now();
      const request = planRequest(tasks, now, blockSnapshot.blocks);
      request.fixedCommitments = today.events.map((e) => ({ startAt: e.startTime, endAt: e.endTime }));
      const replaceExisting = blockSnapshot.blocks.length > 0;
      let nextRun: PlanRun;
      try {
        if (!blockSnapshot.fresh) throw new Error("schedule snapshot unavailable");
        nextRun = await client.plan(request, replaceExisting);
      } catch (serverError) {
        const bridge = engineBridge();
        if (!bridge) throw serverError;
        const raw = await bridge.previewPlan(JSON.stringify(nativePlanRequest(request, replaceExisting)));
        nextRun = JSON.parse(raw) as PlanRun;
        setIsPreview(true);
      }
      setRun(nextRun);
    } catch (e) {
      setError(e instanceof Error ? e.message : "planning failed");
    } finally {
      setBusy(false);
    }
  }

  /** The capacity question (Stage 4.1): the same plan plus a client load to test. The
   *  answer is the engine's own verdict — three numbers and one sentence. */
  async function askCapacity() {
    if (!session || !board || tasks === null || schedule === null) return;
    const hours = Math.max(1, Math.min(168, Number(capacityHours) || 8));
    setCapacityHours(String(hours));
    setCapacityBusy(true);
    setError(null);
    try {
      let today: TodayResponse;
      try {
        const cached = await client.todayCached();
        today = cached.data;
      } catch {
        today = { date: "", events: [], blocks: [], conflicts: [], busyMinutes: 0, freeMinutes: 0 };
      }
      const blockSnapshot = await refreshPlanBlocks();
      const request = planRequest(tasks, Date.now(), blockSnapshot.blocks);
      request.fixedCommitments = today.events.map((e) => ({ startAt: e.startTime, endAt: e.endTime }));
      setCapacity(await client.capacity(request, hours));
    } catch (e) {
      setError(e instanceof Error ? e.message : "capacity failed");
    } finally {
      setCapacityBusy(false);
    }
  }

  async function apply() {
    if (!run || isPreview) return;
    setBusy(true);
    setError(null);
    try {
      const res = await client.apply(run.runId);
      setEntryId(res.entryId);
      setRun(null);
      try {
        const blocks = await client.planBlocks();
        setPlanBlocks(blocks);
        setPlanBlocksFresh(true);
        const cached = await client.portfolioCached();
        setPortfolio(cached.data);
      } catch {
        // The strip can stay stale; the undo button already proves the apply landed.
        setPlanBlocksFresh(false);
        setStale(true);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "apply failed");
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
      try {
        setPlanBlocks(await client.planBlocks());
        setPlanBlocksFresh(true);
        const cached = await client.portfolioCached();
        setPortfolio(cached.data);
      } catch {
        setPlanBlocksFresh(false);
        setStale(true);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "undo failed");
    } finally {
      setBusy(false);
    }
  }

  if (!session) {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.content}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.projectRow}>
        {projects.map((p) => (
          <Pressable
            key={p.id}
            style={[styles.projectChip, p.id === selectedId && styles.projectChipActive]}
            onPress={() => selectProject(p.id)}
            disabled={busy}
          >
            <Text style={[styles.projectChipText, p.id === selectedId && styles.projectChipTextActive]}>
              {p.name}
            </Text>
          </Pressable>
        ))}
      </ScrollView>

      {board && board.tasks.length === 0 ? <Text style={styles.empty}>No tasks yet</Text> : null}
      {stale ? (
        <Text style={styles.stale}>Offline — showing saved data. Plan, Apply and edits need the server.</Text>
      ) : null}
      {board
        ? board.stages.map((stage) => {
            const stageTasks = board.tasks.filter((t) => (t.columnId ?? null) === stage.id);
            if (stageTasks.length === 0) return null;
            return (
              <View key={stage.id}>
                <Text style={styles.stageTitle}>{stage.name}</Text>
                {stageTasks.map((t) => (
                  <View key={t.id} style={styles.taskRow}>
                    <Text style={styles.taskTitle}>{t.title}</Text>
                    <Text style={styles.taskMeta}>
                      {t.effortMinutes != null ? `${t.effortMinutes}m` : "no estimate"}
                    </Text>
                  </View>
                ))}
              </View>
            );
          })
        : null}

      {portfolio ? (
        <View style={styles.portfolio}>
          <Text style={styles.sectionTitle}>This week</Text>
          {portfolio.rows
            .filter((r) => r.weekBlocks.length > 0)
            .map((r) => (
              <View key={r.projectId} style={styles.portfolioRow}>
                <Text numberOfLines={1} style={styles.portfolioLabel}>
                  {r.projectName}
                </Text>
                <View style={styles.portfolioStrip}>
                  {r.weekBlocks.map((b) => {
                    const left = ((b.startAt - weekStart()) / WEEK_MS) * 100;
                    const width = ((b.endAt - b.startAt) / WEEK_MS) * 100;
                    return (
                      <View
                        key={`${b.itemId}-${b.startAt}`}
                        style={[styles.portfolioBar, { left: `${left}%`, width: `${width}%` }]}
                      />
                    );
                  })}
                </View>
              </View>
            ))}
          <Text style={styles.portfolioNote}>{portfolio.note}</Text>
        </View>
      ) : null}

      {portfolio && board ? (
        <WeekGantt
          tasks={board.tasks}
          blocks={portfolio.rows.find((r) => r.projectId === selectedId)?.weekBlocks ?? []}
          projectName={portfolio.rows.find((r) => r.projectId === selectedId)?.projectName ?? "This project"}
        />
      ) : null}

      <View style={styles.addRow}>
        <TextInput
          style={styles.input}
          value={title}
          onChangeText={setTitle}
          placeholder={selectedId ? "Add a task…" : "Pick a project first"}
          placeholderTextColor={Palette.onFaint}
          onSubmitEditing={addTask}
          editable={!!selectedId}
        />
        <Pressable style={styles.addButton} onPress={addTask} disabled={busy || !selectedId}>
          <Text style={styles.addButtonText}>Add</Text>
        </Pressable>
      </View>

      <Pressable
        style={styles.primary}
        onPress={planMyWeek}
        disabled={busy || (tasks?.length ?? 0) === 0}
      >
        <Text style={styles.primaryText}>
          {busy ? "Planning…" : planBlocksFresh && planBlocks.length > 0 ? "Replan my week" : "Plan my week"}
        </Text>
      </Pressable>

      {isPreview ? (
        <Text style={styles.previewNote}>
          Offline preview — the local engine planned this. Apply needs the server.
        </Text>
      ) : null}

      {run ? (
        <View style={styles.proposal}>
          <Text style={styles.sectionTitle}>Proposal</Text>
          {run.result.proposals.map((p) => (
            <View key={p.itemId} style={styles.card}>
              <Text style={styles.cardTime}>
                {formatTime(p.startAt)}–{formatTime(p.endAt)}
              </Text>
              <View style={styles.cardBody}>
                <Text style={styles.cardTitle}>{p.itemId}</Text>
                <Text style={styles.cardReason}>{p.reason}</Text>
              </View>
            </View>
          ))}
          {run.result.unplaced.map((u) => (
            <Text key={u.itemId} style={styles.unplaced}>
              {u.itemId}: {u.reason}
            </Text>
          ))}
          <Text style={styles.health}>{run.result.health.assessment}</Text>
          <View style={styles.proposalActions}>
            {!isPreview ? (
              <Pressable style={styles.applyButton} onPress={apply} disabled={busy}>
                <Text style={styles.applyText}>Apply</Text>
              </Pressable>
            ) : null}
            <Pressable
              style={isPreview ? styles.rejectFull : styles.rejectButton}
              onPress={() => setRun(null)}
              disabled={busy}
            >
              <Text style={styles.rejectText}>
                {isPreview ? "Dismiss preview" : "Reject"}
              </Text>
            </Pressable>
          </View>
        </View>
      ) : null}

      {entryId ? (
        <Pressable style={styles.undoButton} onPress={undo} disabled={busy}>
          <Text style={styles.undoText}>Undo last apply</Text>
        </Pressable>
      ) : null}

      <View style={styles.capacity}>
        <Text style={styles.sectionTitle}>Can I take another client?</Text>
        <View style={styles.capacityRow}>
          <TextInput
            style={styles.capacityInput}
            value={capacityHours}
            onChangeText={setCapacityHours}
            keyboardType="number-pad"
            accessibilityLabel="New client hours per week"
          />
          <Text style={styles.capacityHint}>hours/week</Text>
          <Pressable
            style={styles.capacityButton}
            onPress={askCapacity}
            disabled={capacityBusy || (board?.tasks.length ?? 0) === 0}
          >
            <Text style={styles.capacityButtonText}>{capacityBusy ? "Checking…" : "Check"}</Text>
          </Pressable>
        </View>
        {capacity ? (
          <View style={styles.capacityResult}>
            <Text style={styles.capacitySentence}>{capacity.sentence}</Text>
            <Text style={styles.capacityNumbers}>
              {fmtHours(capacity.availableMinutes)} available · {fmtHours(capacity.plannedMinutes)} planned ·{" "}
              {fmtHours(capacity.spareMinutes)} spare
            </Text>
            {capacity.moves.length > 0 ? (
              <View style={styles.capacityMoves}>
                {capacity.moves.map((m) => (
                  <Text key={m.itemId} style={styles.capacityMove}>
                    {m.title}: {m.unscheduledMinutes}m would move
                  </Text>
                ))}
              </View>
            ) : null}
          </View>
        ) : null}
      </View>

      {error ? <Text style={styles.error}>{error}</Text> : null}
    </ScrollView>
  );
}

/** A week Gantt for the selected project: one row per task, bars placed across the week by
 *  the portfolio's weekBlocks, joined to task titles from the board. A view only — nothing
 *  here writes; a portfolio failure leaves it absent. */
function WeekGantt({ tasks, blocks, projectName }: { tasks: Task[]; blocks: PortfolioResponse["rows"][number]["weekBlocks"]; projectName: string }) {
  const weekLabels = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"];
  const byTask = new Map<string, typeof blocks>();
  for (const b of blocks) {
    const list = byTask.get(b.itemId) ?? [];
    list.push(b);
    byTask.set(b.itemId, list);
  }
  const rows = tasks.filter((t) => byTask.has(t.id));
  if (rows.length === 0) return null;
  return (
    <View style={styles.gantt}>
      <Text style={styles.ganttTitle}>{projectName} — this week</Text>
      <View style={styles.ganttHeader}>
        {weekLabels.map((d, i) => (
          <Text key={d} style={[styles.ganttDay, { left: `${(i / 7) * 100}%` }]}>
            {d}
          </Text>
        ))}
      </View>
      {rows.map((t) => (
        <View key={t.id} style={styles.ganttRow}>
          <Text numberOfLines={1} style={styles.ganttTask}>
            {t.title}
          </Text>
          <View style={styles.ganttTrack}>
            {byTask.get(t.id)!.map((b) => {
              const left = ((b.startAt - weekStart()) / WEEK_MS) * 100;
              const width = ((b.endAt - b.startAt) / WEEK_MS) * 100;
              return (
                <View
                  key={`${b.itemId}-${b.startAt}`}
                  style={[styles.ganttBar, { left: `${left}%`, width: `${width}%` }]}
                />
              );
            })}
          </View>
        </View>
      ))}
    </View>
  );
}

function formatTime(epochMs: number): string {
  const d = new Date(epochMs);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

function fmtHours(minutes: number): string {
  const h = Math.round(minutes / 60);
  return `${h}h`;
}

/*
 * Same keys the JSX already uses, rebuilt on the design tokens.
 *
 * Two things were quietly off-system before: radii of 16/12/10/3 chosen ad hoc
 * rather than the app's four, and `opacity` standing in for muted ink — which
 * over a warm ground washes the hue out instead of using the neutral that was
 * designed for it. Everything the planner draws is work you own, so the accent
 * is right throughout; there are no commitments on this screen to mark.
 */
const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", padding: Space.xl },
  scroll: { flex: 1, backgroundColor: Palette.base },
  content: { padding: Space.lg, gap: Space.sm, paddingBottom: 48 },

  projectRow: { flexGrow: 0, marginBottom: Space.xs },
  projectChip: {
    paddingHorizontal: 14,
    paddingVertical: Space.sm,
    borderRadius: Radius.control,
    borderWidth: 1,
    borderColor: Palette.outline,
    marginRight: Space.sm,
    minHeight: MinimumTouchTarget,
    justifyContent: "center",
  },
  projectChipActive: { backgroundColor: Palette.accent, borderColor: Palette.accent },
  projectChipText: { fontSize: 14, fontWeight: "500", color: Palette.on },
  projectChipTextActive: { color: Palette.onAccent, fontWeight: "600" },

  sectionTitle: { fontSize: 15, lineHeight: 20, fontWeight: "600", letterSpacing: -0.1, color: Palette.on, marginTop: Space.sm, marginBottom: Space.xs },
  stageTitle: {
    fontSize: 11,
    lineHeight: 14,
    fontWeight: "600",
    letterSpacing: 0.4,
    textTransform: "uppercase",
    color: Palette.onFaint,
    marginTop: Space.md,
    marginBottom: 2,
  },
  previewNote: { fontSize: 13, lineHeight: 18, marginTop: Space.md, color: Palette.urgent },

  addRow: { flexDirection: "row", gap: Space.sm, alignItems: "center" },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: Palette.outline,
    borderRadius: Radius.control,
    backgroundColor: Palette.pure,
    paddingHorizontal: Space.md,
    fontSize: 15,
    color: Palette.on,
    minHeight: MinimumTouchTarget,
  },
  addButton: {
    backgroundColor: Palette.surfaceHighest,
    borderRadius: Radius.control,
    paddingHorizontal: Space.lg,
    justifyContent: "center",
    minHeight: MinimumTouchTarget,
  },
  addButtonText: { fontSize: 15, fontWeight: "600", color: Palette.on },

  taskRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    gap: Space.md,
    paddingVertical: Space.md,
    borderBottomWidth: 1,
    borderBottomColor: Palette.outlineSoft,
  },
  taskTitle: { fontSize: 15, lineHeight: 21, flex: 1, fontWeight: "500", color: Palette.on },
  taskMeta: { fontSize: 13, color: Palette.onMuted, fontVariant: ["tabular-nums"] },
  empty: { fontSize: 14, lineHeight: 20, color: Palette.onFaint, paddingVertical: 6 },
  stale: { fontSize: 13, lineHeight: 18, color: Palette.urgent, marginTop: Space.xs },

  primary: {
    marginTop: Space.xl,
    backgroundColor: Palette.accent,
    borderRadius: Radius.control,
    alignItems: "center",
    justifyContent: "center",
    minHeight: MinimumTouchTarget,
  },
  primaryText: { color: Palette.onAccent, fontSize: 15, fontWeight: "600" },

  /* Analysis never writes: the proposal is shown, and Apply is the only thing
     that commits it. */
  proposal: { marginTop: Space.xl, gap: 6 },
  card: { flexDirection: "row", gap: Space.md, paddingVertical: Space.md, alignItems: "baseline" },
  cardTime: { fontSize: 12.5, color: Palette.onMuted, minWidth: 92, fontVariant: ["tabular-nums"] },
  cardBody: { flex: 1, minWidth: 0 },
  cardTitle: { fontSize: 14.5, lineHeight: 20, fontWeight: "500", color: Palette.on },
  cardReason: { fontSize: 12.5, lineHeight: 18, color: Palette.onMuted, marginTop: 2 },
  unplaced: { fontSize: 13, lineHeight: 18, color: Palette.deadline, paddingVertical: Space.xs },
  health: { fontSize: 13, lineHeight: 18, marginTop: Space.sm, color: Palette.onMuted },

  proposalActions: { flexDirection: "row", gap: Space.sm, marginTop: Space.md },
  applyButton: {
    flex: 1,
    backgroundColor: Palette.accent,
    borderRadius: Radius.control,
    alignItems: "center",
    justifyContent: "center",
    minHeight: MinimumTouchTarget,
  },
  applyText: { color: Palette.onAccent, fontSize: 15, fontWeight: "600" },
  rejectButton: {
    flex: 1,
    borderWidth: 1,
    borderColor: Palette.outline,
    borderRadius: Radius.control,
    backgroundColor: Palette.pure,
    alignItems: "center",
    justifyContent: "center",
    minHeight: MinimumTouchTarget,
  },
  rejectFull: {
    flex: 1,
    borderWidth: 1,
    borderColor: Palette.outline,
    borderRadius: Radius.control,
    backgroundColor: Palette.pure,
    alignItems: "center",
    justifyContent: "center",
    minHeight: MinimumTouchTarget,
  },
  rejectText: { fontSize: 15, fontWeight: "500", color: Palette.on },
  undoButton: {
    marginTop: Space.xl,
    borderWidth: 1,
    borderColor: Palette.accent,
    borderRadius: Radius.control,
    alignItems: "center",
    justifyContent: "center",
    minHeight: MinimumTouchTarget,
  },
  undoText: { color: Palette.accent, fontSize: 15, fontWeight: "600" },

  portfolio: { marginTop: Space.xl, gap: 6 },
  portfolioRow: { flexDirection: "row", alignItems: "center", gap: Space.sm },
  portfolioLabel: { width: 84, fontSize: 13, fontWeight: "500", color: Palette.onSoft },
  portfolioStrip: {
    flex: 1,
    height: 14,
    borderRadius: Radius.mark,
    backgroundColor: Palette.surfaceHighest,
    overflow: "hidden",
  },
  portfolioBar: { position: "absolute", top: 0, bottom: 0, borderRadius: Radius.mark, backgroundColor: Palette.accent },
  portfolioNote: { fontSize: 12, lineHeight: 17, color: Palette.onMuted, marginTop: 2 },

  gantt: { marginTop: Space.xl, gap: Space.xs },
  ganttTitle: {
    fontSize: 11,
    lineHeight: 14,
    fontWeight: "600",
    letterSpacing: 0.4,
    textTransform: "uppercase",
    color: Palette.onFaint,
    marginBottom: 2,
  },
  ganttHeader: { height: 16, position: "relative" },
  ganttDay: { position: "absolute", fontSize: 10, color: Palette.onFaint },
  ganttRow: { flexDirection: "row", alignItems: "center", gap: Space.sm, minHeight: 28 },
  ganttTask: { width: 84, fontSize: 12, color: Palette.onSoft, fontWeight: "500" },
  ganttTrack: { flex: 1, height: 12, borderRadius: Radius.mark, backgroundColor: Palette.surfaceHighest, overflow: "hidden" },
  ganttBar: { position: "absolute", top: 0, bottom: 0, borderRadius: Radius.mark, backgroundColor: Palette.accent },

  capacity: { marginTop: Space.xl, gap: 6 },
  capacityRow: { flexDirection: "row", alignItems: "center", gap: Space.sm },
  capacityInput: {
    borderWidth: 1,
    borderColor: Palette.outline,
    borderRadius: Radius.control,
    backgroundColor: Palette.pure,
    paddingHorizontal: Space.md,
    fontSize: 15,
    color: Palette.on,
    minWidth: 72,
    minHeight: MinimumTouchTarget,
    textAlign: "center",
  },
  capacityHint: { fontSize: 13, color: Palette.onMuted },
  capacityButton: {
    flex: 1,
    borderWidth: 1,
    borderColor: Palette.accent,
    borderRadius: Radius.control,
    alignItems: "center",
    justifyContent: "center",
    minHeight: MinimumTouchTarget,
  },
  capacityButtonText: { color: Palette.accent, fontSize: 15, fontWeight: "600" },
  capacityResult: { marginTop: Space.sm, gap: Space.xs },
  capacitySentence: { fontSize: 15, lineHeight: 21, fontWeight: "600", color: Palette.on },
  capacityNumbers: { fontSize: 13, color: Palette.onMuted, fontVariant: ["tabular-nums"] },
  capacityMoves: { marginTop: Space.xs, gap: 2 },
  capacityMove: { fontSize: 13, lineHeight: 18, color: Palette.onMuted },

  error: { color: Palette.deadline, fontSize: 14, lineHeight: 20, marginTop: Space.md },
});
