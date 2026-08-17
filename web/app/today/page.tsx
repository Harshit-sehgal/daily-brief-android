"use client";

import { useCallback, useEffect, useState } from "react";
import { client, savedSession, ApiError } from "@/lib/api";
import type { TodayResponse } from "@/lib/types";

function fmt(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

export default function TodayPage() {
  const session = savedSession();
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setToday(await client.today());
  }, []);

  useEffect(() => {
    if (!session) return;
    refresh().catch((e) => setError(e instanceof Error ? e.message : "load failed"));
  }, [session, refresh]);

  if (!session) {
    return <div className="card"><p className="muted">Sign in on the Planner page first.</p></div>;
  }

  return (
    <>
      <div className="card">
        <div className="row">
          <h2 style={{ margin: 0 }}>Today</h2>
          <span className="tag">{today ? `${today.busyMinutes} busy · ${today.freeMinutes} free` : "…"}</span>
        </div>

        {error && <p className="error">{error}</p>}

        {today?.conflicts.map((c, i) => (
          <div className="conflict" key={i}>
            Overlap: <strong>{c.first.title}</strong> × <strong>{c.second.title}</strong> — {Math.round(c.overlapMs / 60_000)} min
            <div className="muted">{fmt(c.first.startTime)}–{fmt(c.first.endTime)} and {fmt(c.second.startTime)}–{fmt(c.second.endTime)}</div>
          </div>
        ))}

        {today && today.conflicts.length === 0 && <p className="muted">No calendar conflicts today.</p>}
      </div>

      <div className="card">
        <h2>Schedule</h2>
        {today === null ? (
          <p className="muted">loading…</p>
        ) : (
          <table>
            <thead>
              <tr><th>Time</th><th>What</th><th>Source</th></tr>
            </thead>
            <tbody>
              {[...today.events, ...today.blocks]
                .map((item) => ({
                  id: item.id,
                  startTime: "startTime" in item ? item.startTime : item.startAt,
                  endTime: "endTime" in item ? item.endTime : item.endAt,
                  title: "title" in item ? item.title || item.id : item.planItemId,
                  allDay: "isAllDay" in item && item.isAllDay,
                  locked: "locked" in item && item.locked,
                  source: "source" in item ? item.source : "plan",
                }))
                .sort((a, b) => a.startTime - b.startTime)
                .map((item) => (
                <tr key={item.id}>
                  <td style={{ whiteSpace: "nowrap" }}>{fmt(item.startTime)} → {fmt(item.endTime)}</td>
                  <td>
                    {item.title}
                    {item.allDay && <span className="tag" style={{ marginLeft: 8 }}>all day</span>}
                    {item.locked && <span className="tag" style={{ marginLeft: 8 }}>locked</span>}
                  </td>
                  <td className="muted">{item.source}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}