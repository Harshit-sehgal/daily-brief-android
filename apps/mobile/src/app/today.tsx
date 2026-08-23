import { useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { Palette, Radius, Space } from "@/constants/palette";
import { hm, ui } from "@/constants/ui";
import { client, savedSession } from "@/lib/api";
import type { ExternalEvent, SummaryResponse, TodayBlock, TodayResponse } from "@/lib/types";

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

/** The time spine as a vertical timeline: the day's hours on an axis, carrying
 *  the same ownership marks as the rest of the app — a commitment is outlined
 *  in honey because the planner may not move it, a block is filled with the
 *  accent because it is yours. A view only; nothing here writes. */
function DayTimeline({ date, events, blocks }: { date: string; events: ExternalEvent[]; blocks: TodayBlock[] }) {
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
              {formatTime(b.startAt)} {b.title}
            </Text>
          </View>
        );
      })}
    </View>
  );
}

/** Every row on the day in one list, in the order it happens. Two lists headed
 *  "Calendar" and "Plan blocks" made the reader interleave them by hand to
 *  answer the only question the screen exists for: what is next. */
type DayRow = {
  key: string;
  start: number;
  end: number;
  title: string;
  meta: string;
  owned: boolean;
  isDeadline: boolean;
};

export default function TodayScreen() {
  const router = useRouter();
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [summary, setSummary] = useState<SummaryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [stale, setStale] = useState(false);

  const refresh = useCallback(async () => {
    try {
      if (!(await savedSession())) {
        router.replace("/");
        return;
      }
      setError(null);
      const cached = await client.todayCached();
      setToday(cached.data);
      setStale(cached.stale);
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
      <View style={ui.centre}>
        <ActivityIndicator color={Palette.accent} />
        {error ? <Text style={ui.error}>{error}</Text> : null}
      </View>
    );
  }

  const events = [...today.events].sort((a, b) => a.startTime - b.startTime);
  const blocks = [...today.blocks].sort((a, b) => a.startAt - b.startAt);

  const rows: DayRow[] = [
    ...events.map((e) => ({
      key: `event-${e.id}`,
      start: e.startTime,
      end: e.endTime,
      title: e.title,
      meta: e.source,
      owned: false,
      isDeadline: Boolean(e.isDeadline),
    })),
    ...blocks.map((b) => ({
      key: `block-${b.id}`,
      start: b.startAt,
      end: b.endAt,
      title: b.title,
      meta: b.projectName ?? "your work",
      owned: true,
      isDeadline: false,
    })),
  ].sort((a, b) => a.start - b.start);

  const total = Math.max(1, today.busyMinutes + today.freeMinutes);
  const quotaSpent = summary !== null && summary.used >= summary.limit;

  return (
    <ScrollView style={ui.screen} contentContainerStyle={ui.content}>
      <View style={styles.head}>
        <View style={styles.headText}>
          <Text style={ui.eyebrow}>{today.date}</Text>
          <Text style={ui.h1}>Today</Text>
        </View>
        <Pressable style={ui.btnQuiet} onPress={syncCalendar} disabled={busy} accessibilityRole="button">
          <Text style={ui.btnQuietText}>{busy ? "Syncing…" : "Sync"}</Text>
        </Pressable>
      </View>

      {stale ? (
        <Text style={styles.stale}>
          Offline — showing the last synced day. Sync and writes need the server.
        </Text>
      ) : null}

      <View style={ui.card}>
        <Text style={ui.eyebrow}>How the day divides</Text>
        <View
          style={ui.meter}
          accessibilityRole="image"
          accessibilityLabel={`${today.busyMinutes} minutes busy, ${today.freeMinutes} minutes free.`}
        >
          <View style={[ui.meterFixed, { flex: Math.max(0.0001, today.busyMinutes / total) }]} />
          <View style={[ui.meterFree, { flex: Math.max(0.0001, today.freeMinutes / total) }]} />
        </View>
        <View style={ui.legend}>
          <Text style={ui.legendItem}>Busy {hm(today.busyMinutes)}</Text>
          <Text style={ui.legendItem}>Free {hm(today.freeMinutes)}</Text>
        </View>
      </View>

      {today.conflicts.length > 0 ? (
        <View style={ui.clash}>
          <Text style={ui.clashHead}>
            {today.conflicts.length} clash{today.conflicts.length === 1 ? "" : "es"} today
          </Text>
          {today.conflicts.map((c, i) => (
            <Text key={i} style={ui.clashBody}>
              {c.first.title} runs into {c.second.title} for {Math.round(c.overlapMs / 60_000)} min.
            </Text>
          ))}
        </View>
      ) : null}

      <View style={ui.sectionRule}>
        <Text style={ui.eyebrow}>Schedule</Text>
        <View style={ui.rule} />
      </View>

      {rows.length === 0 ? (
        <Text style={ui.empty}>Nothing on the calendar and nothing planned.</Text>
      ) : (
        rows.map((r) => (
          <View key={r.key} style={ui.row}>
            <Text style={ui.rowTime}>
              {formatTime(r.start)}–{formatTime(r.end)}
            </Text>
            <View style={r.owned ? ui.ownPlan : ui.ownFixed} />
            <View style={ui.rowBody}>
              <Text numberOfLines={1} style={r.owned ? ui.rowTitleOwned : ui.rowTitle}>
                {r.title}
              </Text>
              <Text style={r.owned ? ui.rowMeta : ui.rowMetaFixed}>
                {r.meta}
                {r.isDeadline ? " · deadline" : ""}
              </Text>
            </View>
            <Text style={ui.rowTrail}>{hm((r.end - r.start) / 60_000)}</Text>
          </View>
        ))
      )}

      <View style={ui.sectionRule}>
        <Text style={ui.eyebrow}>The day at true proportion</Text>
        <View style={ui.rule} />
      </View>
      <ScrollView style={styles.timelineScroll} nestedScrollEnabled>
        <DayTimeline date={today.date} events={events} blocks={blocks} />
      </ScrollView>

      <View style={ui.sectionRule}>
        <Text style={ui.eyebrow}>Daily brief</Text>
        <View style={ui.rule} />
      </View>
      {summary ? (
        <View style={ui.card}>
          <Text style={ui.body}>{summary.text}</Text>
          <Text style={ui.floorNote}>
            {summary.used} of {summary.limit} used this month
            {quotaSpent ? " · the monthly quota is spent" : ""}
          </Text>
        </View>
      ) : (
        <Text style={ui.muted}>A written summary of the day, on request.</Text>
      )}
      {!quotaSpent ? (
        <Pressable style={[ui.btn, styles.spaced]} onPress={writeBrief} disabled={busy} accessibilityRole="button">
          <Text style={ui.btnText}>{busy ? "Writing…" : "Write today's brief"}</Text>
        </Pressable>
      ) : null}

      <Pressable
        style={[ui.btnPrimary, styles.spacedWide]}
        onPress={() => router.push("/planner")}
        accessibilityRole="button"
      >
        <Text style={ui.btnPrimaryText}>Open planner</Text>
      </Pressable>

      {error ? <Text style={ui.error}>{error}</Text> : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  head: { flexDirection: "row", alignItems: "flex-start", gap: Space.md, marginBottom: Space.xs },
  headText: { flex: 1, minWidth: 0 },
  stale: { fontSize: 13, lineHeight: 18, color: Palette.urgent },
  spaced: { marginTop: Space.sm },
  spacedWide: { marginTop: Space.xl },
  timelineScroll: {
    height: 400,
    borderRadius: Radius.block,
    borderWidth: 1,
    borderColor: Palette.outlineSoft,
    backgroundColor: Palette.pure,
  },
  timeline: { height: 24 * HOUR_DP, position: "relative" },
  hourLine: { position: "absolute", left: 0, right: 0, borderTopWidth: 1, borderTopColor: Palette.outlineSoft },
  hourLabel: { position: "absolute", top: 2, right: 8, fontSize: 11, color: Palette.onFaint },
  tlBar: {
    position: "absolute",
    left: 48,
    right: 8,
    borderRadius: Radius.mark,
    paddingHorizontal: 8,
    justifyContent: "center",
    overflow: "hidden",
  },
  /* A commitment: outlined in honey, because the planner may not move it. */
  tlEvent: {
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: Palette.urgent,
    borderLeftWidth: 3,
    backgroundColor: "transparent",
  },
  tlEventTitle: { fontSize: 12, fontWeight: "500", color: Palette.urgent },
  /* A block: filled with the accent, because it is yours and can be moved. */
  tlBlock: { backgroundColor: Palette.accentWash, borderLeftWidth: 3, borderLeftColor: Palette.accent },
  tlBlockTitle: { fontSize: 12, fontWeight: "600", color: Palette.accent },
});
