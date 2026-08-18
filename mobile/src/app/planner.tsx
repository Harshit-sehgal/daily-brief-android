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

import { client, savedSession } from "@/lib/api";
import type { Board, PlanRun, PlanningRequest, PortfolioResponse, Project, Task, TodayResponse } from "@/lib/types";

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

function weekWindows() {
  const days = [1, 2, 3, 4, 5];
  return days.map((dayOfWeek) => ({
    id: `w${dayOfWeek}`,
    dayOfWeek,
    startMinute: 9 * 60,
    endMinute: 17 * 60,
    rank: dayOfWeek,
  }));
}

export default function PlannerScreen() {
  const router = useRouter();
  const [session, setSession] = useState<{ token: string; workspaceId: string } | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [board, setBoard] = useState<Board | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [run, setRun] = useState<PlanRun | null>(null);
  const [isPreview, setIsPreview] = useState(false);
  const [entryId, setEntryId] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [portfolio, setPortfolio] = useState<PortfolioResponse | null>(null);

  const loadBoard = useCallback(async (projectId: string) => {
    setBoard(await client.board(projectId));
  }, []);

  const refresh = useCallback(async () => {
    const s = await savedSession();
    if (!s) {
      router.replace("/");
      return;
    }
    setSession(s);
    try {
      const ps = await client.listProjects();
      setProjects(ps);
      const first = ps.find((p) => p.isDefault)?.id ?? ps[0]?.id ?? null;
      setSelectedId((current) => current && ps.some((p) => p.id === current) ? current : first);
      if (first) await loadBoard(first);
      // The strip is a view, not a control: a portfolio failure must not blank the board.
      try {
        setPortfolio(await client.portfolio());
      } catch {
        setPortfolio(null);
      }
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
      await loadBoard(id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "load failed");
    }
  }

  function planRequest(tasks: Task[], now: number): PlanningRequest {
    const rangeStart = now - (now % DAY);
    return {
      v: 1,
      workspaceId: session!.workspaceId,
      rangeStartMs: rangeStart,
      rangeEndMs: rangeStart + 7 * DAY,
      nowMs: rangeStart,
      items: tasks.map((t, i) => ({
        ...t,
        boardId: selectedId ?? "b1",
        rank: t.rank ?? i,
        effortMinutes: t.effortMinutes ?? undefined,
      })),
      blocks: [],
      fixedCommitments: [],
      dependencies: [],
      scheduleIdByTaskId: {},
      preferredOrder: [],
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

  /** The client composes fixedCommitments from Today's events, as the web client does:
   *  the server stays a pure planner, and the calendar's busy time is the client's reading.
   *  When the server is unreachable, the same computation runs locally through the native
   *  bridge (same Mapping, same engine) — preview only: Apply needs the server. */
  async function planMyWeek() {
    if (!session || !board) return;
    setBusy(true);
    setError(null);
    setRun(null);
    setIsPreview(false);
    try {
      let today: TodayResponse;
      try {
        today = await client.today();
      } catch {
        today = { date: "", events: [], blocks: [], conflicts: [], busyMinutes: 0, freeMinutes: 0 };
      }
      const now = Date.now();
      const request = planRequest(board.tasks, now);
      request.fixedCommitments = today.events.map((e) => ({ startAt: e.startTime, endAt: e.endTime }));
      let nextRun: PlanRun;
      try {
        nextRun = await client.plan(request);
      } catch (serverError) {
        const bridge = engineBridge();
        if (!bridge) throw serverError;
        const raw = await bridge.previewPlan(JSON.stringify(request));
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

  async function apply() {
    if (!run || isPreview) return;
    setBusy(true);
    setError(null);
    try {
      const res = await client.apply(run.runId);
      setEntryId(res.entryId);
      setRun(null);
      try {
        setPortfolio(await client.portfolio());
      } catch {
        // The strip can stay stale; the undo button already proves the apply landed.
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

      {portfolio && portfolio.rows.some((r) => r.weekBlocks.length > 0) ? (
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

      <View style={styles.addRow}>
        <TextInput
          style={styles.input}
          value={title}
          onChangeText={setTitle}
          placeholder={selectedId ? "Add a task…" : "Pick a project first"}
          placeholderTextColor="#9a9aa2"
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
        disabled={busy || (board?.tasks.length ?? 0) === 0}
      >
        <Text style={styles.primaryText}>{busy ? "Planning…" : "Plan my week"}</Text>
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

      {error ? <Text style={styles.error}>{error}</Text> : null}
    </ScrollView>
  );
}

function formatTime(epochMs: number): string {
  const d = new Date(epochMs);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24 },
  scroll: { flex: 1 },
  content: { padding: 20, gap: 8, paddingBottom: 48 },
  projectRow: { flexGrow: 0, marginBottom: 4 },
  projectChip: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: "#c8c8d0",
    marginRight: 8,
    minHeight: 36,
    justifyContent: "center",
  },
  projectChipActive: { backgroundColor: "#3c87f7", borderColor: "#3c87f7" },
  projectChipText: { fontSize: 14, fontWeight: "600" },
  projectChipTextActive: { color: "#ffffff" },
  stageTitle: { fontSize: 13, fontWeight: "700", opacity: 0.6, marginTop: 10, marginBottom: 2 },
  previewNote: { fontSize: 13, marginTop: 12, opacity: 0.7, color: "#b8860b" },
  addRow: { flexDirection: "row", gap: 8, alignItems: "center" },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: "#c8c8d0",
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
    minHeight: 48,
  },
  addButton: {
    backgroundColor: "#e8e8ee",
    borderRadius: 10,
    paddingHorizontal: 16,
    justifyContent: "center",
    minHeight: 48,
  },
  addButtonText: { fontSize: 15, fontWeight: "600" },
  taskRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 10,
  },
  taskTitle: { fontSize: 15, flex: 1, fontWeight: "500" },
  taskMeta: { fontSize: 13, opacity: 0.6 },
  empty: { fontSize: 14, opacity: 0.5, paddingVertical: 6 },
  primary: {
    marginTop: 20,
    backgroundColor: "#3c87f7",
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: "center",
    minHeight: 48,
  },
  primaryText: { color: "#ffffff", fontSize: 16, fontWeight: "600" },
  proposal: { marginTop: 24, gap: 6 },
  sectionTitle: { fontSize: 16, fontWeight: "700", marginTop: 8, marginBottom: 4 },
  card: { flexDirection: "row", gap: 12, paddingVertical: 10, alignItems: "baseline" },
  cardTime: { fontSize: 13, opacity: 0.6, minWidth: 92 },
  cardBody: { flex: 1 },
  cardTitle: { fontSize: 15, fontWeight: "500" },
  cardReason: { fontSize: 13, opacity: 0.6, marginTop: 2 },
  unplaced: { fontSize: 13, opacity: 0.6, paddingVertical: 4 },
  health: { fontSize: 13, marginTop: 8, opacity: 0.8 },
  proposalActions: { flexDirection: "row", gap: 8, marginTop: 12 },
  applyButton: {
    flex: 1,
    backgroundColor: "#3c87f7",
    borderRadius: 10,
    paddingVertical: 12,
    alignItems: "center",
    minHeight: 48,
  },
  applyText: { color: "#ffffff", fontSize: 15, fontWeight: "600" },
  rejectButton: {
    flex: 1,
    borderWidth: 1,
    borderColor: "#c8c8d0",
    borderRadius: 10,
    paddingVertical: 12,
    alignItems: "center",
    minHeight: 48,
  },
  rejectFull: {
    flex: 1,
    borderWidth: 1,
    borderColor: "#c8c8d0",
    borderRadius: 10,
    paddingVertical: 12,
    alignItems: "center",
    minHeight: 48,
  },
  rejectText: { fontSize: 15, fontWeight: "600" },
  undoButton: {
    marginTop: 20,
    borderWidth: 1,
    borderColor: "#3c87f7",
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: "center",
    minHeight: 48,
  },
  undoText: { color: "#3c87f7", fontSize: 15, fontWeight: "600" },
  portfolio: { marginTop: 20, gap: 6 },
  portfolioRow: { flexDirection: "row", alignItems: "center", gap: 8 },
  portfolioLabel: {
    width: 84,
    fontSize: 13,
    fontWeight: "600",
    opacity: 0.8,
  },
  portfolioStrip: {
    flex: 1,
    height: 14,
    borderRadius: 3,
    backgroundColor: "#e8e8ee",
    overflow: "hidden",
  },
  portfolioBar: {
    position: "absolute",
    top: 0,
    bottom: 0,
    borderRadius: 3,
    backgroundColor: "#3c87f7",
  },
  portfolioNote: { fontSize: 12, opacity: 0.6, marginTop: 2 },
  error: { color: "#d93a3a", fontSize: 14, marginTop: 12 },
});