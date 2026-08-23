"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { client, savedSession } from "@/lib/api";
import type { TodayResponse } from "@/lib/types";

function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
}

function duration(ms: number): string {
  const minutes = Math.max(0, Math.round(ms / 60_000));
  const h = Math.floor(minutes / 60);
  return h > 0 ? `${h}h${minutes % 60 ? ` ${minutes % 60}m` : ""}` : `${minutes}m`;
}

/** Every row on the day, in one list, ordered by when it starts. A block you own
 *  carries a solid accent bar; a commitment carries a dashed honey outline —
 *  the same two marks the planner and the week grid use. */
type DayRow = {
  id: string;
  startTime: number;
  endTime: number;
  title: string;
  allDay: boolean;
  locked: boolean;
  source: string;
  owned: boolean;
};

export default function TodayPage() {
  const session = savedSession();
  const workspaceId = session?.workspaceId;
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setToday(await client.today());
  }, []);

  useEffect(() => {
    if (!workspaceId) return;
    refresh().catch((e) => setError(e instanceof Error ? e.message : "load failed"));
  }, [workspaceId, refresh]);

  if (!session) {
    return (
      <>
        <div className="topbar">
          <span className="crumb-current">Today</span>
        </div>
        <div className="desk">
          <main className="view">
            <p className="muted">Sign in on the Plan page first.</p>
          </main>
        </div>
      </>
    );
  }

  const rows: DayRow[] = today
    ? [
        ...today.events.map((event) => ({
          id: event.id,
          startTime: event.startTime,
          endTime: event.endTime,
          title: event.title || event.id,
          allDay: Boolean(event.isAllDay),
          locked: false,
          source: event.source,
          owned: false,
        })),
        ...today.blocks.map((block) => ({
          id: block.id,
          startTime: block.startAt,
          endTime: block.endAt,
          title: block.title || block.planItemId,
          allDay: false,
          locked: Boolean(block.locked),
          source: "plan",
          owned: true,
        })),
      ].sort((a, b) => a.startTime - b.startTime)
    : [];

  const clash = today?.conflicts[0] ?? null;
  const busy = today?.busyMinutes ?? 0;
  const free = today?.freeMinutes ?? 0;
  const total = Math.max(1, busy + free);

  return (
    <>
      <div className="topbar">
        <span className="crumb">Plan</span>
        <span className="crumb-sep">/</span>
        <span className="crumb-current">Today</span>
        <div className="topbar-actions">
          <Link className="btn" href="/#planner">Back to the week</Link>
        </div>
      </div>

      <div className="desk">
        <main className="view">
          <div className="view-head">
            <h1>Today</h1>
            <p className="muted">
              {new Date().toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" })}
              {today
                ? ` · ${today.events.length} commitment${today.events.length === 1 ? "" : "s"} · ${today.blocks.length} block${today.blocks.length === 1 ? "" : "s"} placed`
                : " · loading…"}
            </p>
          </div>

          {error && <p className="error" role="alert" style={{ marginTop: 12 }}>{error}</p>}

          <div className="section-rule" style={{ marginTop: 8 }}>
            <span className="lbl">Schedule</span>
          </div>

          {today === null ? (
            <p className="muted" style={{ padding: "0 10px" }}>loading…</p>
          ) : rows.length === 0 ? (
            <p className="muted" style={{ padding: "0 10px" }}>Nothing on the calendar and nothing placed.</p>
          ) : (
            <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
              {rows.map((item) => (
                <div className="row" key={`${item.source}-${item.id}`}>
                  <span className="row-time">
                    {item.allDay ? "all day" : `${clock(item.startTime)}–${clock(item.endTime)}`}
                  </span>
                  <span className={item.owned ? "own-plan" : "own-fixed"} aria-hidden="true" />
                  <span className="row-title" style={item.owned ? { fontWeight: 500 } : undefined}>
                    {item.title}
                  </span>
                  <span className={item.owned ? "row-meta" : "row-meta src-fixed"}>
                    {item.owned ? "your work" : item.source}
                    {item.locked && " · locked"}
                  </span>
                  <span className="row-meta num" style={{ width: 52, textAlign: "right" }}>
                    {item.allDay ? "" : duration(item.endTime - item.startTime)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </main>

        <aside className="aside" aria-label="Needs attention">
          <div className="card att-now">
            <span className="lbl">How the day divides</span>
            <div
              className="meter"
              role="img"
              aria-label={`${busy} minutes busy, ${free} minutes free.`}
            >
              <span className="meter-fixed" style={{ width: `${(busy / total) * 100}%` }} />
              <span className="meter-free" style={{ flex: 1 }} />
            </div>
            <div className="legend">
              <span>Busy <strong>{duration(busy * 60_000)}</strong></span>
              <span>Free <strong>{duration(free * 60_000)}</strong></span>
            </div>
          </div>

          {clash ? (
            <div className="clash att-alert">
              <p className="clash-head">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
                  <path d="M12 8.5v5M12 17v.01M10.3 3.9 2.6 17.4A1.8 1.8 0 0 0 4.2 20h15.6a1.8 1.8 0 0 0 1.6-2.6L13.7 3.9a1.8 1.8 0 0 0-3.4 0z" />
                </svg>
                {today!.conflicts.length} clash{today!.conflicts.length === 1 ? "" : "es"} today
              </p>
              {today!.conflicts.map((c, i) => (
                <p className="clash-body" key={i}>
                  <strong>{c.first.title}</strong> runs into <strong>{c.second.title}</strong> for{" "}
                  <span className="num">{Math.round(c.overlapMs / 60_000)} min</span> —{" "}
                  <span className="num">{clock(c.first.startTime)}–{clock(c.first.endTime)}</span> against{" "}
                  <span className="num">{clock(c.second.startTime)}–{clock(c.second.endTime)}</span>.
                </p>
              ))}
              <div className="clash-acts">
                <Link className="btn" href="/#planner">Replan the week</Link>
              </div>
            </div>
          ) : (
            today !== null && (
              <div className="card att-rest">
                <span className="lbl">Clashes</span>
                <p className="muted" style={{ marginTop: 6 }}>
                  Your calendar and your planned work agree today.
                </p>
              </div>
            )
          )}
        </aside>
      </div>
    </>
  );
}
