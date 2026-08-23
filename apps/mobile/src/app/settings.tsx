import { useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";

import { Palette, Radius, Space } from "@/constants/palette";
import { ui } from "@/constants/ui";
import { API_BASE, clearSession, client, savedSession } from "@/lib/api";
import { unregisterPushToken } from "@/lib/push";
import type { WorkSchedule, WorkWindow } from "@/lib/types";

const WEEK_DAYS = [
  { dayOfWeek: 1, label: "Sunday" },
  { dayOfWeek: 2, label: "Monday" },
  { dayOfWeek: 3, label: "Tuesday" },
  { dayOfWeek: 4, label: "Wednesday" },
  { dayOfWeek: 5, label: "Thursday" },
  { dayOfWeek: 6, label: "Friday" },
  { dayOfWeek: 7, label: "Saturday" },
];

function minutesToTime(minutes: number | undefined): string {
  if (minutes == null) return "";
  return `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

function timeToMinutes(value: string): number | null {
  if (!/^\d{2}:\d{2}$/.test(value)) return null;
  const [hours, minutes] = value.split(":").map(Number);
  return hours <= 23 && minutes <= 59 ? hours * 60 + minutes : null;
}

function weeklyWindows(schedule: WorkSchedule, dayOfWeek: number): WorkWindow[] {
  return schedule.windows.filter((window) => (window.kind ?? "weekly") === "weekly" && window.dayOfWeek === dayOfWeek);
}

function replaceWeeklyWindow(schedule: WorkSchedule, dayOfWeek: number, startMinute: number | null, endMinute: number | null): WorkSchedule {
  const existing = weeklyWindows(schedule, dayOfWeek);
  const other = schedule.windows.filter((window) => !((window.kind ?? "weekly") === "weekly" && window.dayOfWeek === dayOfWeek));
  if (startMinute == null || endMinute == null) return { ...schedule, windows: other };
  const first = existing[0] ?? {
    id: `${schedule.id}-weekly-${dayOfWeek}-1`,
    kind: "weekly" as const,
    dayOfWeek,
    localDate: null,
    startMinute,
    endMinute,
    isClosed: false,
    rank: dayOfWeek,
  };
  return {
    ...schedule,
    windows: [
      ...other,
      { ...first, kind: "weekly", dayOfWeek, localDate: null, startMinute, endMinute, isClosed: false },
      ...existing.slice(1),
    ],
  };
}

export default function SettingsScreen() {
  const router = useRouter();
  const [workspaceId, setWorkspaceId] = useState<string | null>(null);
  const [scheduleDraft, setScheduleDraft] = useState<WorkSchedule | null>(null);
  const [scheduleStale, setScheduleStale] = useState(false);
  const [scheduleMessage, setScheduleMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    (async () => {
      const s = await savedSession();
      if (!s) {
        router.replace("/");
        return;
      }
      setWorkspaceId(s.workspaceId);
      try {
        const planningSettings = await client.planningSettingsCached();
        setScheduleDraft(planningSettings.data);
        setScheduleStale(planningSettings.stale);
      } catch (e) {
        setError(e instanceof Error ? e.message : "working-hours load failed");
      }
    })();
  }, [router]);

  async function saveSchedule() {
    if (!scheduleDraft) return;
    setBusy(true);
    setError(null);
    setScheduleMessage(null);
    try {
      const saved = await client.updatePlanningSettings(scheduleDraft);
      setScheduleDraft(saved);
      setScheduleStale(false);
      setScheduleMessage("Working hours saved.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "working-hours save failed");
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    // Leave the tenant's device registry before the session dies: the workspace must stop
    // ringing this phone. A failure here is safe — the token is upserted, not trusted.
    await unregisterPushToken();
    await clearSession();
    router.replace("/");
  }

  return (
    <ScrollView style={ui.screen} contentContainerStyle={ui.content}>
      <Text style={ui.h1}>Settings</Text>

      <View style={ui.sectionRule}>
        <Text style={ui.eyebrow}>Working hours</Text>
        <View style={ui.rule} />
      </View>
      <Text style={ui.muted}>
        Plans place work only inside these local wall-clock hours. Calendar events stay fixed
        wherever they fall.
      </Text>

      {scheduleDraft ? (
        <>
          <View style={styles.setting}>
            <Text style={styles.settingName}>Time zone</Text>
            <View style={styles.controlRow}>
              <TextInput
                style={[ui.input, styles.grow]}
                value={scheduleDraft.timeZoneId}
                onChangeText={(timeZoneId) => setScheduleDraft({ ...scheduleDraft, timeZoneId })}
                autoCapitalize="none"
                autoCorrect={false}
                accessibilityLabel="Working-hours timezone"
              />
              <Pressable
                style={ui.btn}
                accessibilityRole="button"
                onPress={() => {
                  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
                  if (zone) setScheduleDraft({ ...scheduleDraft, timeZoneId: zone });
                }}
              >
                <Text style={ui.btnText}>Use device</Text>
              </Pressable>
            </View>
          </View>

          {WEEK_DAYS.map(({ dayOfWeek, label }) => {
            const window = weeklyWindows(scheduleDraft, dayOfWeek)[0];
            const closed = window == null;
            return (
              <View key={dayOfWeek} style={styles.dayRow}>
                <Text style={[styles.dayLabel, closed && styles.dayLabelClosed]}>{label}</Text>
                <TextInput
                  style={[ui.input, styles.timeInput]}
                  value={minutesToTime(window?.startMinute)}
                  onChangeText={(value) => setScheduleDraft(replaceWeeklyWindow(scheduleDraft, dayOfWeek, timeToMinutes(value), window?.endMinute ?? 1020))}
                  placeholder="09:00"
                  placeholderTextColor={Palette.onFaint}
                  keyboardType="numbers-and-punctuation"
                  accessibilityLabel={`${label} start`}
                />
                <Text style={styles.dash}>–</Text>
                <TextInput
                  style={[ui.input, styles.timeInput]}
                  value={minutesToTime(window?.endMinute)}
                  onChangeText={(value) => setScheduleDraft(replaceWeeklyWindow(scheduleDraft, dayOfWeek, window?.startMinute ?? 540, timeToMinutes(value)))}
                  placeholder="17:00"
                  placeholderTextColor={Palette.onFaint}
                  keyboardType="numbers-and-punctuation"
                  accessibilityLabel={`${label} end`}
                />
              </View>
            );
          })}

          {scheduleStale ? (
            <Text style={styles.offline}>Offline — showing saved settings. Saving needs the server.</Text>
          ) : null}

          <Pressable
            style={[ui.btnPrimary, styles.spaced]}
            onPress={saveSchedule}
            disabled={busy}
            accessibilityRole="button"
          >
            <Text style={ui.btnPrimaryText}>{busy ? "Saving…" : "Save working hours"}</Text>
          </Pressable>
          {scheduleMessage ? (
            <Text style={styles.success} accessibilityLiveRegion="polite">{scheduleMessage}</Text>
          ) : null}
        </>
      ) : (
        <Text style={ui.muted}>Loading working hours…</Text>
      )}

      {error ? <Text style={ui.error}>{error}</Text> : null}

      <View style={ui.sectionRule}>
        <Text style={ui.eyebrow}>Workspace</Text>
        <View style={ui.rule} />
      </View>
      <View style={styles.setting}>
        <Text style={styles.settingName}>Workspace</Text>
        <Text style={styles.mono}>{workspaceId ?? "…"}</Text>
      </View>
      <View style={styles.setting}>
        <Text style={styles.settingName}>Server</Text>
        <Text style={styles.mono}>{API_BASE}</Text>
      </View>

      {/* Signing out unregisters this device from the workspace, so it reads as
          the consequential action it is rather than another quiet row. */}
      <Pressable style={styles.signOut} onPress={signOut} accessibilityRole="button">
        <Text style={styles.signOutText}>Sign out</Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  setting: { paddingVertical: Space.md, borderBottomWidth: 1, borderBottomColor: Palette.outlineSoft, gap: 6 },
  settingName: { fontSize: 14, lineHeight: 20, fontWeight: "500", color: Palette.on },
  controlRow: { flexDirection: "row", alignItems: "center", gap: Space.sm },
  grow: { flex: 1 },
  mono: { fontSize: 13, color: Palette.onMuted, fontFamily: "monospace" },
  dayRow: { flexDirection: "row", alignItems: "center", gap: 6, marginTop: Space.sm },
  dayLabel: { width: 82, fontSize: 14, color: Palette.on },
  dayLabelClosed: { color: Palette.onFaint },
  timeInput: { width: 82, textAlign: "center" },
  dash: { fontSize: 15, color: Palette.onFaint },
  offline: { color: Palette.urgent, fontSize: 13, lineHeight: 18, marginTop: Space.sm },
  success: { color: Palette.positive, fontSize: 14, marginTop: Space.sm },
  spaced: { marginTop: Space.lg },
  signOut: {
    marginTop: Space.xxl,
    borderWidth: 1,
    borderColor: Palette.deadline,
    borderRadius: Radius.control,
    minHeight: 48,
    alignItems: "center",
    justifyContent: "center",
  },
  signOutText: { color: Palette.deadline, fontSize: 15, fontWeight: "600" },
});
