"use client";

import Link from "next/link";
import { usePlanner } from "@/lib/planner";
import { addDays, clock, dayKey, hm, longDate } from "@/lib/format";
import { SignedIn } from "../components/sign-in";
import { AppliedNote, ProposalReview } from "../components/proposal";
import { DayNotes } from "../components/day-notes";
import { DayTimeline, type DayEntry } from "../components/day-timeline";
import { Exclaim } from "../components/marks";

/*
 * Today, as a diary page: the hours down the left and what fills them. The margin
 * holds only what is worth a note — a clash, and the written brief when asked for.
 * Plan my day lives here, where the day is.
 */
export default function TodayPage() {
  return (
    <SignedIn>
      <Today />
    </SignedIn>
  );
}

function Today() {
  const p = usePlanner();
  const dayStart = p.todayStart;
  const dayEnd = addDays(dayStart, 1);
  const events = (p.events ?? []).filter((e) => e.startTime < dayEnd && e.endTime > dayStart);
  const blocks = p.planBlocks.filter((b) => b.startAt < dayEnd && b.endAt > dayStart);

  const entries: DayEntry[] = [
    ...events
      .filter((e) => !e.isAllDay)
      .map((e) => ({ key: `e-${e.id}`, kind: "fixed" as const, start: e.startTime, end: e.endTime, title: e.title || "Busy", meta: e.location || null })),
    ...blocks.map((b) => ({
      key: `b-${b.id}`,
      kind: "plan" as const,
      start: b.startAt,
      end: b.endAt,
      title: p.taskTitle(b.taskId),
      meta: p.projectOfTask(b.taskId),
      itemId: b.taskId,
    })),
    ...(p.run?.result.proposals ?? [])
      .filter((d) => d.startAt < dayEnd && d.endAt > dayStart)
      .map((d) => ({ key: `d-${d.itemId}-${d.startAt}`, kind: "draft" as const, start: d.startAt, end: d.endAt, title: p.taskTitle(d.itemId), meta: "proposed", itemId: d.itemId })),
  ];
  const allDay = events.filter((e) => e.isAllDay);
  const conflicts = p.clashes;
  const todayWindow = p.windowsOn(dayStart)[0] ?? null;
  const loaded = p.events !== null;

  return (
    <>
      <header className="page-head">
        <div className="page-title">
          <h1>Today</h1>
          <p className="page-date">{longDate(dayStart)}</p>
        </div>
        <div className="page-actions">
          <button className="btn btn-ink" onClick={() => p.plan("day")} disabled={p.busy !== null || !p.canPlan}>
            {p.busy === "plan-day" ? "Planning…" : blocks.length ? "Replan my day" : "Plan my day"}
          </button>
        </div>
      </header>

      <AppliedNote />
      <ProposalReview />

      <div className="day-page">
        <section className="day-sheet" aria-label="Schedule">
          {allDay.length > 0 && (
            <ul className="all-day">
              {allDay.map((e) => (
                <li key={e.id}>
                  <span className="all-day-when">All day</span>
                  <span className="mark mark-fixed" aria-hidden="true" />
                  <span className="all-day-title">{e.title}</span>
                </li>
              ))}
            </ul>
          )}
          {!loaded ? (
            <div className="ruled-blank" aria-busy="true" />
          ) : (
            <DayTimeline dayStart={dayStart} entries={entries} window={todayWindow} conflicts={conflicts} now={p.now} />
          )}
        </section>

        <aside className="margin-notes" aria-label="Notes on the day">
          {conflicts.length > 0 && (
            <section className="note note-clash">
              <h2>{conflicts.length === 1 ? "A clash" : "Clashes"}</h2>
              {conflicts.map((c, i) => (
                <div className="red-note" key={i}>
                  <Exclaim />
                  <p>
                    <strong>{c.first.title}</strong> runs into <strong>{c.second.title}</strong> for {hm(c.overlapMs / 60_000)},{" "}
                    <span className="num">{clock(c.second.start)}–{clock(c.second.start + c.overlapMs)}</span>.
                  </p>
                </div>
              ))}
              <p className="print-note">
                Move one in your calendar, then <Link href="/">replan</Link>.
              </p>
            </section>
          )}

          {p.connections && !p.connections.calendar.connected && (
            <section className="note">
              <p className="nudge">
                See your meetings here.
                <button className="btn btn-quiet" onClick={() => p.connect("calendar")} disabled={p.busy !== null}>
                  Connect calendar
                </button>
              </p>
            </section>
          )}

          <section className="note">
            {p.summary ? (
              <>
                <h2>The brief</h2>
                <p className="brief">{p.summary.text}</p>
                {p.summary.used >= p.summary.limit && <p className="floor-note">That was this month’s last brief.</p>}
              </>
            ) : (
              <button className="btn" onClick={p.writeBrief} disabled={p.busy !== null || !loaded}>
                {p.busy === "brief" ? "Writing…" : "Write today’s brief"}
              </button>
            )}
          </section>

          <DayNotes key={dayStart} day={dayKey(dayStart)} />
        </aside>
      </div>
    </>
  );
}
