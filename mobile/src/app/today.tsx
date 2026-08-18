import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { client, savedSession } from "@/lib/api";
import type { ScheduledBlock, SummaryResponse, TodayResponse } from "@/lib/types";

function formatTime(epochMs: number): string {
  const d = new Date(epochMs);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

const HOUR_DP = 48;
const DAY_MS = 86_400_000;

function dayStartUtc(date: string): number {
  const [y, m, d] = date.split("-").map(Number);
  return Date.UTC(y, m - 1, d);
}

/** The time spine as a vertical timeline: the day's hours on an axis, events outlined and
 *  plan blocks filled, both positioned by their wall-clock minutes. A view only — nothing
 *  here writes. */
function DayTimeline({ date, events, blocks }: { date: string; events: TodayResponse["events"]; blocks: ScheduledBlock[] }) {
  const dayStart = dayStartUtc(date);
  const dayEnd = dayStart + DAY_MS;
  const height = 24 * HOUR_DP;
  const topOf = (ms: number) => Math.max(0, Math.min(24 * HOUR_DP, ((ms - dayStart) / DAY_MS) * height));
  const hourLabels = Array.from({ length: 8 }, (_, i) => i * 3); // 00, 03, …, 21
  const inDay = (start: number, end: number) => start < dayEnd && end > dayStart;
  return (
    <View style={styles.timeline}>
      {hourLabels.map((h) => (
        <View key={h} style={[styles.hourLine, { top: h * HOUR_DP }]}>
          <Text style={styles.hourLabel}>{String(h).padStart(2, "0")}</Text>
        </View>
      ))}
      {events.filter((e) => inDay(e.startTime, e.endTime)).map((e) => {
        const top = topOf(e.startTime);
        const h = Math.max(8, topOf(e.endTime) - top);
        return (
          <View key={e.id} style={[styles.tlBar, styles.tlEvent, { top, height: h }]}>
            <Text numberOfLines={1} style={styles.tlEventTitle}>
              {formatTime(e.startTime)} {e.title}
            </Text>
          </View>
        );
      })}
      {blocks.filter((b) => inDay(b.startAt, b.endAt)).map((b) => {
        const top = topOf(b.startAt);
        const h = Math.max(8, topOf(b.endAt) - top);
        return (
          <View key={b.id} style={[styles.tlBar, styles.tlBlock, { top, height: h }]}>
            <Text numberOfLines={1} style={styles.tlBlockTitle}>
              {formatTime(b.startAt)} {b.planItemId}
            </Text>
          </View>
        );
      })}
    </View>
  );
}

export default function TodayScreen() {
  const router = useRouter();
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [summary, setSummary] = useState<SummaryResponse | null>(null);
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
      setSummary(null);
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

  async function writeBrief() {
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

      {summary ? (
        <View style={styles.brief}>
          <Text style={styles.briefText}>{summary.text}</Text>
          <Text style={styles.briefQuota}>
            Daily brief · {summary.used} of {summary.limit} used this month
            {summary.used >= summary.limit ? " · the monthly quota is spent" : ""}
          </Text>
        </View>
      ) : null}
      {!summary || (summary.used ?? 0) < (summary.limit ?? 3) ? (
        <Pressable style={styles.primary} onPress={writeBrief} disabled={busy}>
          <Text style={styles.primaryText}>{busy ? "Writing…" : "Write today's brief"}</Text>
        </Pressable>
      ) : null}

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

      <Text style={styles.sectionTitle}>Timeline</Text>
      <ScrollView style={styles.timelineScroll} nestedScrollEnabled>
        <DayTimeline date={today.date} events={events} blocks={blocks} />
      </ScrollView>

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
  brief: {
    borderWidth: 1,
    borderColor: "#c8c8d0",
    borderRadius: 12,
    padding: 12,
    gap: 6,
    marginBottom: 8,
  },
  briefText: { fontSize: 15, lineHeight: 21 },
  briefQuota: { fontSize: 13, opacity: 0.6 },
  sectionTitle: { fontSize: 16, fontWeight: "700", marginTop: 16, marginBottom: 4 },
  card: { flexDirection: "row", gap: 12, paddingVertical: 10, alignItems: "baseline" },
  cardTime: { fontSize: 13, opacity: 0.6, minWidth: 92 },
  cardTitle: { fontSize: 15, fontWeight: "500", flex: 1 },
  deadline: { fontSize: 12, color: "#d93a3a", fontWeight: "700" },
  empty: { fontSize: 14, opacity: 0.5, paddingVertical: 6 },
  conflicts: { backgroundColor: "#fdf0f0", borderRadius: 8, padding: 12, marginTop: 8 },
  conflictText: { fontSize: 13, color: "#a03030", marginTop: 2 },
  timelineScroll: { height: 400, borderRadius: 8, borderWidth: 1, borderColor: "#e0e0e6" },
  timeline: { height: 24 * HOUR_DP, position: "relative" },
  hourLine: { position: "absolute", left: 0, right: 0, borderTopWidth: 1, borderTopColor: "#ececf0" },
  hourLabel: { position: "absolute", top: 2, right: 8, fontSize: 11, opacity: 0.5 },
  tlBar: {
    position: "absolute",
    left: 48,
    right: 8,
    borderRadius: 4,
    paddingHorizontal: 8,
    justifyContent: "center",
    overflow: "hidden",
  },
  tlEvent: { borderWidth: 1, borderColor: "#3c87f7", backgroundColor: "#eaf2fe" },
  tlEventTitle: { fontSize: 12, fontWeight: "600", color: "#1c4f9c" },
  tlBlock: { backgroundColor: "#3c87f7" },
  tlBlockTitle: { fontSize: 12, fontWeight: "600", color: "#ffffff" },
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