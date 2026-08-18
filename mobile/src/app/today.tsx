import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { client, savedSession } from "@/lib/api";
import type { TodayResponse } from "@/lib/types";

function formatTime(epochMs: number): string {
  const d = new Date(epochMs);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export default function TodayScreen() {
  const router = useRouter();
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      if (!(await savedSession())) {
        router.replace("/");
        return;
      }
      setError(null);
      setToday(await client.today());
    } catch (e) {
      setError(e instanceof Error ? e.message : "load failed");
    }
  }, [router]);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  async function syncCalendar() {
    setBusy(true);
    setError(null);
    try {
      await client.reconcile();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "sync failed");
    } finally {
      setBusy(false);
    }
  }

  if (!today) {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
        {error ? <Text style={styles.error}>{error}</Text> : null}
      </View>
    );
  }

  const events = [...today.events].sort((a, b) => a.startTime - b.startTime);
  const blocks = [...today.blocks].sort((a, b) => a.startAt - b.startAt);

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.content}>
      <View style={styles.row}>
        <Text style={styles.date}>{today.date}</Text>
        <Pressable style={styles.syncButton} onPress={syncCalendar} disabled={busy}>
          <Text style={styles.syncText}>{busy ? "Syncing…" : "Sync calendar"}</Text>
        </Pressable>
      </View>
      <Text style={styles.stats}>
        {today.busyMinutes} busy · {today.freeMinutes} free
      </Text>

      {today.conflicts.length > 0 ? (
        <View style={styles.conflicts}>
          <Text style={styles.sectionTitle}>Clashes</Text>
          {today.conflicts.map((c, i) => (
            <Text key={i} style={styles.conflictText}>
              {c.first.title} overlaps {c.second.title}
            </Text>
          ))}
        </View>
      ) : null}

      <Text style={styles.sectionTitle}>Calendar</Text>
      {events.length === 0 ? <Text style={styles.empty}>No events today</Text> : null}
      {events.map((e) => (
        <View key={e.id} style={styles.card}>
          <Text style={styles.cardTime}>
            {formatTime(e.startTime)}–{formatTime(e.endTime)}
          </Text>
          <Text style={styles.cardTitle}>{e.title}</Text>
          {e.isDeadline ? <Text style={styles.deadline}>deadline</Text> : null}
        </View>
      ))}

      <Text style={styles.sectionTitle}>Plan blocks</Text>
      {blocks.length === 0 ? <Text style={styles.empty}>Nothing planned yet</Text> : null}
      {blocks.map((b) => (
        <View key={b.id} style={styles.card}>
          <Text style={styles.cardTime}>
            {formatTime(b.startAt)}–{formatTime(b.endAt)}
          </Text>
          <Text style={styles.cardTitle}>{b.planItemId}</Text>
        </View>
      ))}

      <Pressable style={styles.primary} onPress={() => router.push("/planner")}>
        <Text style={styles.primaryText}>Open planner</Text>
      </Pressable>
      {error ? <Text style={styles.error}>{error}</Text> : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 12, padding: 24 },
  scroll: { flex: 1 },
  content: { padding: 20, gap: 8, paddingBottom: 48 },
  row: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  date: { fontSize: 24, fontWeight: "700" },
  syncButton: { minHeight: 36, justifyContent: "center", paddingHorizontal: 12 },
  syncText: { color: "#3c87f7", fontSize: 14, fontWeight: "600" },
  stats: { fontSize: 14, opacity: 0.6, marginBottom: 8 },
  sectionTitle: { fontSize: 16, fontWeight: "700", marginTop: 16, marginBottom: 4 },
  card: { flexDirection: "row", gap: 12, paddingVertical: 10, alignItems: "baseline" },
  cardTime: { fontSize: 13, opacity: 0.6, minWidth: 92 },
  cardTitle: { fontSize: 15, fontWeight: "500", flex: 1 },
  deadline: { fontSize: 12, color: "#d93a3a", fontWeight: "700" },
  empty: { fontSize: 14, opacity: 0.5, paddingVertical: 6 },
  conflicts: { backgroundColor: "#fdf0f0", borderRadius: 8, padding: 12, marginTop: 8 },
  conflictText: { fontSize: 13, color: "#a03030", marginTop: 2 },
  primary: {
    marginTop: 24,
    backgroundColor: "#3c87f7",
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: "center",
    minHeight: 48,
  },
  primaryText: { color: "#ffffff", fontSize: 16, fontWeight: "600" },
  error: { color: "#d93a3a", fontSize: 14, marginTop: 12 },
});