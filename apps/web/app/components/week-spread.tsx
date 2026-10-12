"use client";

import { useState } from "react";
import { usePlanner, type Planner } from "@/lib/planner";
import { addDays, clock, count, hm, longDate, weekday } from "@/lib/format";
import { jotLines, parseJot } from "@/lib/jot";
import { useCanHover } from "@/lib/use-phone";
import type { Task } from "@/lib/types";
import { CarryToDay, useCarry } from "./drag";
import { JotLine, USUAL_EFFORT } from "./jot";
import { Circled, Exclaim, Tick } from "./marks";
import { TaskBullet, TaskFacts, taskClass } from "./task";

/*
 * The week as a planner's two-page spread: Monday to Thursday on the left leaf, the
 * inbox and then Friday to Sunday on the right, rows aligned across the binding the
 * way a printed week is. The inbox holds only what still needs a place; a task the
 * planner has booked is on its day. Each day is a full-width row of lines rather than one of
 * seven narrow columns, which is what let every title be read in full — the
 * column grid truncated nearly all of them and pushed Sunday off the page.
 *
 * Below the width of two pages (phones and narrow tablets) the spread closes
 * differently: a strip of the seven days on top — the week's shape at a glance —
 * and only the chosen day open beneath it. Stacking all seven days made a reader
 * scroll past five finished days to reach today.
 *
 * An inbox task can be carried onto a day (or sent there from its card, or touched
 * with the pencil). The planner pencils it in around everything already there, and
 * the answer — Apply or Discard, or why it did not fit — is written in that day.
 *
 * Three kinds of line, three marks:
 *   solid ink stroke  — your own work, which the planner may move
 *   dashed amber      — a calendar commitment, which it may not
 *   dotted pencil     — a block the planner has proposed and you have not applied
 */

type Entry = {
  key: string;
  kind: "plan" | "fixed" | "draft";
  start: number;
  end: number;
  title: string;
  project?: string | null;
  allDay?: boolean;
  /** The task behind a block of your own work or a proposal; a calendar event has none. */
  itemId?: string;
};

const KIND_WORDS = { plan: "your work", fixed: "calendar commitment", draft: "proposed, not applied" } as const;

/** Everything a page needs to print one local day: its lines and how full it is. */
function readDay(p: Planner, day: number) {
  const next = addDays(day, 1);
  const entries = ([
    ...p.weekBlocks
      .filter((b) => b.startAt < next && b.endAt > day)
      .map((b) => ({ key: `b-${b.itemId}-${b.startAt}`, kind: "plan" as const, start: b.startAt, end: b.endAt, title: b.title, project: b.projectName, itemId: b.itemId })),
    ...(p.events ?? [])
      .filter((e) => e.startTime < next && e.endTime > day)
      .map((e) => ({ key: `e-${e.id}`, kind: "fixed" as const, start: e.startTime, end: e.endTime, title: e.title || "Busy", allDay: Boolean(e.isAllDay) })),
    ...(p.run?.result.proposals ?? [])
      .filter((d) => d.startAt < next && d.endAt > day)
      .map((d) => ({ key: `d-${d.itemId}-${d.startAt}`, kind: "draft" as const, start: d.startAt, end: d.endAt, title: p.taskTitle(d.itemId), project: p.projectOfTask(d.itemId), itemId: d.itemId })),
  ] as Entry[]).sort((a, b) => Number(Boolean(b.allDay)) - Number(Boolean(a.allDay)) || a.start - b.start);

  const planned = Math.round(
    entries
      .filter((e) => e.kind === "plan")
      .reduce((sum, e) => sum + (Math.min(e.end, next) - Math.max(e.start, day)), 0) / 60_000,
  );
  const capacity = p.capacityMinutes(day);
  const isPast = next <= p.todayStart;
  return {
    next,
    entries,
    planned,
    capacity,
    over: capacity > 0 && planned > capacity,
    isToday: day === p.todayStart,
    isPast,
    // A task can be carried to a day that has working hours and has not ended.
    droppable: !isPast && capacity > 0,
  };
}

/** How full a day is, in words. */
function loadWords(r: ReturnType<typeof readDay>): React.ReactNode {
  if (r.capacity === 0) return r.planned > 0 ? `${hm(r.planned)} on a day off` : "Day off";
  if (r.over) return <>{hm(r.planned)} of {hm(r.capacity)}, <strong>over by {hm(r.planned - r.capacity)}</strong></>;
  if (r.planned === 0) return r.isPast ? "" : `${hm(r.capacity)} open`;
  return `${hm(r.planned)} of ${hm(r.capacity)}`;
}

export function WeekSpread() {
  const p = usePlanner();
  return (
    <CarryToDay onPlace={(taskId, day) => p.placeOnDay(taskId, day)}>
      <div className="spread" aria-label="This week, day by day">
        {/* Monday to Thursday on the left leaf; the inbox heads the right one, beside
            Monday, so what still needs a place is in view with the week it goes into
            rather than under Sunday, below the fold. */}
        {p.week.days.slice(0, 4).map((day) => (
          <Day key={day} day={day} />
        ))}
        <Inbox />
        {p.week.days.slice(4).map((day) => (
          <Day key={day} day={day} />
        ))}
      </div>
    </CarryToDay>
  );
}

/** The one-leaf week: seven days as a strip, the chosen one open, then the inbox. */
export function StripWeek() {
  const p = usePlanner();
  const [chosen, setChosen] = useState(p.todayStart);
  // A task pencilled in from its card or with the pencil lands on a day of its own
  // choosing; open that day so the answer is in view.
  const landed = p.run && p.runOrigin.kind === "drop" ? p.runOrigin.day : null;
  const [seen, setSeen] = useState<number | null>(null);
  if (landed !== seen) {
    setSeen(landed);
    if (landed !== null && p.week.days.includes(landed)) setChosen(landed);
  }
  // After midnight on Sunday the strip turns to a new week; fall back to today.
  // After midnight on Sunday, or on a turned page, the chosen day is not in view:
  // open today if this week has it, else the week's Monday.
  const day = p.week.days.includes(chosen) ? chosen : p.week.days.includes(p.todayStart) ? p.todayStart : p.week.days[0];
  return (
    <CarryToDay
      onPlace={(taskId, d) => {
        // Open the day the task went to, so its pencilled line and answer are in view.
        if (p.week.days.includes(d)) {
          setChosen(d);
          document.querySelector(".week-strip")?.scrollIntoView({ behavior: "smooth", block: "start" });
        }
        return p.placeOnDay(taskId, d);
      }}
    >
      <div className="strip-week">
        <WeekStrip day={day} onChoose={setChosen} />
        <Day key={day} day={day} compact />
        <Inbox />
      </div>
    </CarryToDay>
  );
}

function WeekStrip({ day, onChoose }: { day: number; onChoose: (d: number) => void }) {
  const p = usePlanner();
  const carry = useCarry();
  return (
    <div className="week-strip" role="group" aria-label="Choose a day">
      <TurnWeek by={-1} />
      {p.week.days.map((d) => {
        const r = readDay(p, d);
        const fill = r.capacity > 0 ? Math.min(1, r.planned / r.capacity) : r.planned > 0 ? 1 : 0;
        return (
          <button
            key={d}
            type="button"
            data-drop-day={r.droppable ? d : undefined}
            className={[
              "ws-day",
              r.isToday && "is-today",
              r.isPast && "is-past",
              r.over && "is-over",
              r.capacity === 0 && "is-off",
              carry?.drag && r.droppable && "is-droppable",
              carry?.drag?.over === d && "is-drop-target",
            ]
              .filter(Boolean)
              .join(" ")}
            aria-pressed={d === day}
            aria-label={`${longDate(d)}${r.isToday ? ", today" : ""}. ${
              r.capacity === 0 ? "Day off" : `${hm(r.planned)} planned of ${hm(r.capacity)}`
            }`}
            onClick={() => onChoose(d)}
          >
            <span className="ws-letter" aria-hidden="true">{new Date(d).toLocaleDateString([], { weekday: "narrow" })}</span>
            <span className="ws-num" aria-hidden="true">
              {new Date(d).getDate()}
              {r.isToday && <Circled />}
            </span>
            <span className="ws-bar" aria-hidden="true">
              {fill > 0 && <span style={{ height: `${Math.max(12, fill * 100)}%` }} />}
            </span>
          </button>
        );
      })}
      <TurnWeek by={1} />
    </div>
  );
}

/** Turn the page a week back or on. */
export function TurnWeek({ by }: { by: 1 | -1 }) {
  const p = usePlanner();
  const end = by === -1 ? p.weekOffset <= -4 : p.weekOffset >= 8;
  return (
    <button type="button" className="turn-week" onClick={() => p.turnWeek(by)} disabled={end} aria-label={by === -1 ? "The week before" : "The week after"}>
      <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
        <path d={by === -1 ? "M14.600 5.400c-2.400 2-4.500 4.200-6.300 6.700 1.900 2.300 4 4.500 6.400 6.500" : "M9.400 5.400c2.400 2 4.500 4.200 6.300 6.700-1.900 2.300-4 4.500-6.400 6.500"} />
      </svg>
    </button>
  );
}

function Day({ day, compact = false }: { day: number; compact?: boolean }) {
  const p = usePlanner();
  const carry = useCarry();
  const r = readDay(p, day);
  const headingId = `day-${day}`;

  return (
    <section
      className={[
        "day",
        compact && "day-compact",
        r.isToday && "is-today",
        r.isPast && "is-past",
        r.over && "is-over",
        carry?.drag && r.droppable && "is-droppable",
        carry?.drag?.over === day && "is-drop-target",
      ]
        .filter(Boolean)
        .join(" ")}
      aria-labelledby={headingId}
      data-drop-day={r.droppable ? day : undefined}
    >
      <header className="day-head">
        {compact ? (
          // The strip above already shows the date, so the open day names itself in pen.
          <h2 id={headingId} className="day-title">
            <span className="day-name-pen">{r.isToday ? "Today" : weekday(day)}</span>
            {r.isToday && <span className="vh">, {weekday(day)}</span>}
          </h2>
        ) : (
          <h2 id={headingId} className="day-title">
            <span className="day-num">
              {new Date(day).getDate()}
              {r.isToday && <Circled />}
            </span>
            <span className="day-name">
              {weekday(day)}
              {r.isToday && <span className="vh"> (today)</span>}
            </span>
          </h2>
        )}
        <p className="day-load num">{loadWords(r)}</p>
      </header>
      {r.entries.length > 0 && (
        <ul className="entries">
          {r.entries.map((e) => (
            <DayEntryLine key={e.key} entry={e} day={day} next={r.next} />
          ))}
        </ul>
      )}
      <DropAnswer day={day} />
      <div className="day-lines" aria-hidden="true" />
    </section>
  );
}

function DayEntryLine({ entry: e, day, next }: { entry: Entry; day: number; next: number }) {
  const p = usePlanner();
  const task = e.itemId ? p.task(e.itemId) : null;
  return (
    <li className={["entry", `entry-${e.kind}`, task && taskClass(task)].filter(Boolean).join(" ")}>
      <span className="entry-time num">
        {e.allDay ? "all day" : clock(Math.max(e.start, day))}
        {/* A diary writes a range as start over end; the phone shows the end, the
            desktop shows the length in its own column instead. */}
        {!e.allDay && <span className="entry-end">{clock(Math.min(e.end, next))}</span>}
      </span>
      <span className={`mark mark-${e.kind}`} aria-hidden="true" />
      <span className="entry-text">
        {task ? (
          <button
            type="button"
            className="entry-title entry-open tool-target"
            // The eraser on a pencilled line rubs out the pencil, not the task.
            onClick={() => (e.kind === "draft" && p.tool === "eraser" ? void p.discard() : p.touchTask(task.id))}
          >
            <span className="hl">{e.title}</span>
          </button>
        ) : (
          <span className="entry-title">{e.title}</span>
        )}
        {e.project && <span className="entry-project">{e.project}</span>}
      </span>
      <span className="entry-len num">{e.allDay ? "" : hm((Math.min(e.end, next) - Math.max(e.start, day)) / 60_000)}</span>
      <span className="vh">, {KIND_WORDS[e.kind]}</span>
    </li>
  );
}

/** The planner's answer to a task carried onto this day, written in the day itself. */
function DropAnswer({ day }: { day: number }) {
  const p = usePlanner();
  const carry = useCarry();
  if (carry?.placing === day && p.busy === "place") {
    return (
      <p className="drop-answer drop-pending" role="status">
        Pencilling it in…
      </p>
    );
  }
  const origin = p.runOrigin;
  if (!p.run || origin.kind !== "drop" || origin.day !== day) return null;
  const placed = p.run.result.proposals;
  const title = p.taskTitle(origin.taskId);
  if (placed.length === 0) {
    const why = p.run.result.unplaced[0]?.reason ?? "There is no free time left in this day's working hours.";
    return (
      <div className="drop-answer drop-miss" role="status">
        <Exclaim />
        <p>
          <strong>{title}</strong> did not fit. {why}
        </p>
        <button className="btn btn-quiet" onClick={p.discard} disabled={p.busy !== null}>
          Dismiss
        </button>
      </div>
    );
  }
  const first = Math.min(...placed.map((x) => x.startAt));
  const last = Math.max(...placed.map((x) => x.endAt));
  return (
    <div className="drop-answer" role="status">
      <p>
        Pencilled in{" "}
        <span className="num">
          {clock(first)}–{clock(last)}
        </span>
        .
      </p>
      <div className="actions">
        <button className="btn btn-ink" onClick={p.apply} disabled={p.busy !== null}>
          {p.busy === "apply" ? "Applying…" : "Apply"}
        </button>
        <button className="btn btn-quiet" onClick={p.discard} disabled={p.busy !== null}>
          Discard
        </button>
      </div>
    </div>
  );
}

function Inbox() {
  const p = usePlanner();
  const [textOver, setTextOver] = useState(false);
  const [dropped, setDropped] = useState<string | null>(null);
  const hover = useCanHover();

  // Text dragged in from anywhere — an email subject, a list in a note — is jotted,
  // one task per line, the same as a pasted list.
  const takesText = (e: React.DragEvent) => e.dataTransfer.types.includes("text/plain");

  async function jotDropped(text: string) {
    const projects = (p.projects ?? []).filter((x) => !x.isDefault).map((x) => ({ id: x.id, name: x.name }));
    const jots = jotLines(text).map((line) => {
      const j = parseJot(line, p.now, projects);
      return { ...j, effortMinutes: j.effortMinutes ?? USUAL_EFFORT };
    });
    const n = await p.jot(jots);
    setDropped(n === 0 ? null : n === 1 ? "Captured the dropped text." : `Captured ${n} tasks from the dropped text.`);
  }

  return (
    <section
      className={["day inbox", textOver && "is-text-over"].filter(Boolean).join(" ")}
      id="inbox"
      aria-labelledby="inbox-title"
      onDragOver={(e) => {
        if (!takesText(e)) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = "copy";
        setTextOver(true);
      }}
      onDragLeave={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node)) setTextOver(false);
      }}
      onDrop={(e) => {
        if (!takesText(e)) return;
        e.preventDefault();
        setTextOver(false);
        void jotDropped(e.dataTransfer.getData("text/plain"));
      }}
    >
      <header className="day-head">
        <h2 id="inbox-title" className="day-title">
          <span className="inbox-name">Inbox</span>
        </h2>
      </header>
      {/* An empty notebook on a desk opens with the pen already on the line. */}
      <JotLine key={p.tasks === null ? "opening" : "open"} label="Inbox task title" placeholder="Jot a task: Call Asha 20m fri…" focusOnMount={hover && p.tasks?.length === 0} />
      {dropped && (
        <p className="ink-note inbox-dropped" role="status">
          <Tick /> {dropped}
        </p>
      )}
      {p.inbox.length > 0 && (
        <ul className="entries inbox-list">
          {p.inbox.map((t) => (
            <InboxRow key={t.id} task={t} hover={hover} />
          ))}
        </ul>
      )}
      {textOver && (
        <p className="text-drop-hint" aria-hidden="true">
          Drop to jot each line as a task
        </p>
      )}
      <div className="day-lines" aria-hidden="true" />
    </section>
  );
}

function InboxRow({ task: t, hover }: { task: Task; hover: boolean }) {
  const p = usePlanner();
  const carry = useCarry();
  const done = Boolean(t.completedAt);
  const carried = carry?.drag?.item.id === t.id;
  const canCarry = !done && carry !== null;
  const item = { id: t.id, title: t.title };

  return (
    <li
      className={["entry entry-todo", taskClass(t), canCarry && "is-carryable", carried && "is-carried"]
        .filter(Boolean)
        .join(" ")}
      // A mouse picks the whole row up; a finger holds the title, so a swipe still scrolls.
      onPointerDown={canCarry && hover ? (e) => carry.begin(e, item, true) : undefined}
    >
      <TaskBullet task={t} />
      <span className="entry-text">
        <button
          type="button"
          className="entry-title entry-open tool-target"
          onPointerDown={canCarry && !hover ? (e) => carry.begin(e, item, true) : undefined}
          onClick={() => {
            if (!carry?.justDropped()) p.touchTask(t.id);
          }}
          onContextMenu={hover ? undefined : (e) => e.preventDefault()}
        >
          <span className="hl">{t.title}</span>
        </button>
      </span>
      <span className="entry-len">
        <TaskFacts task={t} />
      </span>
    </li>
  );
}
