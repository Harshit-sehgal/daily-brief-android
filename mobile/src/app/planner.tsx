import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import { client, savedSession } from "@/lib/api";
import type { PlanRun, Task, TodayResponse } from "@/lib/types";

const DAY = 86_400_000;

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
  const [tasks, setTasks] = useState<Task[]>([]);
  const [run, setRun] = useState<PlanRun | null>(null);
  const [entryId, setEntryId] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const s = await savedSession();
    if (!s) {
      router.replace("/");
      return;
    }
    setSession(s);
    try {
      setTasks(await client.listTasks());
    } catch (e) {
      setError(e instanceof Error ? e.message : "load failed");
    }
  }, [router]);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  async function addTask() {
    if (!title.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await client.addTask({
        id: newTaskId(),
        boardId: "b1",
        title: title.trim(),
        rank: tasks.length,
      });
      setTitle("");
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "add failed");
    } finally {
      setBusy(false);
    }
  }

  /** The client composes fixedCommitments from Today's events, as the web client does:
   *  the server stays a pure planner, and the calendar's busy time is the client's reading. */
  async function planMyWeek() {
    if (!session) return;
    setBusy(true);
    setError(null);
    setRun(null);
    try {
      let today: TodayResponse;
      try {
        today = await client.today();
      } catch {
        today = { date: "", events: [], blocks: [], conflicts: [], busyMinutes: 0, freeMinutes: 0 };
      }
      const now = Date.now();
      const rangeStart = now - (now % DAY);
      const nextRun = await client.plan({
        v: 1,
        workspaceId: session.workspaceId,
        rangeStartMs: rangeStart,
        rangeEndMs: rangeStart + 7 * DAY,
        nowMs: rangeStart,
        items: tasks.map((t, i) => ({
          ...t,
          boardId: "b1",
          rank: t.rank ?? i,
          effortMinutes: t.effortMinutes ?? undefined,
        })),
        blocks: [],
        fixedCommitments: today.events.map((e) => ({ startAt: e.startTime, endAt: e.endTime })),
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
      });
      setRun(nextRun);
    } catch (e) {
      setError(e instanceof Error ? e.message : "planning failed");
    } finally {
      setBusy(false);
    }
  }

  async function apply() {
    if (!run) return;
    setBusy(true);
    setError(null);
    try {
      const res = await client.apply(run.runId);
      setEntryId(res.entryId);
      setRun(null);
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
      <View style={styles.addRow}>
        <TextInput
          style={styles.input}
          value={title}
          onChangeText={setTitle}
          placeholder="Add a task…"
          placeholderTextColor="#9a9aa2"
          onSubmitEditing={addTask}
        />
        <Pressable style={styles.addButton} onPress={addTask} disabled={busy}>
          <Text style={styles.addButtonText}>Add</Text>
        </Pressable>
      </View>

      {tasks.length === 0 ? <Text style={styles.empty}>No tasks yet</Text> : null}
      {tasks.map((t) => (
        <View key={t.id} style={styles.taskRow}>
          <Text style={styles.taskTitle}>{t.title}</Text>
          <Text style={styles.taskMeta}>
            {t.effortMinutes != null ? `${t.effortMinutes}m` : "no estimate"}
          </Text>
        </View>
      ))}

      <Pressable style={styles.primary} onPress={planMyWeek} disabled={busy || tasks.length === 0}>
        <Text style={styles.primaryText}>{busy ? "Planning…" : "Plan my week"}</Text>
      </Pressable>

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
            <Pressable style={styles.applyButton} onPress={apply} disabled={busy}>
              <Text style={styles.applyText}>Apply</Text>
            </Pressable>
            <Pressable
              style={styles.rejectButton}
              onPress={() => setRun(null)}
              disabled={busy}
            >
              <Text style={styles.rejectText}>Reject</Text>
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
  error: { color: "#d93a3a", fontSize: 14, marginTop: 12 },
});