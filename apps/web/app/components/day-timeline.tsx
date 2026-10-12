"use client";

import { layoutLanes, ruledHours, type Clash } from "@/lib/day-layout";
import { usePlanner } from "@/lib/planner";
import { taskClass } from "./task";
import { clock, HOUR, hm } from "@/lib/format";

/*
 * A day page ruled by the hour, as a paper diary is. Entries sit at their time
 * and are as tall as they last, so the shape of the day — where it is crowded,
 * where it is open — reads before any title does. Two entries that overlap are
 * written side by side, and a red-pen bracket in the margin names the overlap in
 * words; colour is never the only signal of a clash.
 */

export type DayEntry = {
  key: string;
  kind: "plan" | "fixed" | "draft";
  start: number;
  end: number;
  title: string;
  meta?: string | null;
  /** The task behind a block of your own work or a proposal; touching it uses the tool in hand. */
  itemId?: string;
};

const KIND_WORDS = { plan: "your work", fixed: "calendar commitment", draft: "proposed, not applied" } as const;

type Vars = React.CSSProperties & Record<`--${string}`, string | number>;

export function DayTimeline({
  dayStart,
  entries,
  window,
  conflicts,
  now,
}: {
  dayStart: number;
  entries: DayEntry[];
  window?: { startMinute: number; endMinute: number } | null;
  conflicts: Clash<{ start: number; end: number }>[];
  now: number;
}) {
  const p = usePlanner();
  const { from, to } = ruledHours(dayStart, entries, window);
  const top0 = dayStart + from * HOUR;
  const hours = Array.from({ length: to - from }, (_, i) => from + i);
  const at = (ms: number) => (ms - top0) / HOUR; // in hours from the top rule
  const laned = layoutLanes(entries.map((e) => ({ ...e, start: Math.max(e.start, top0), end: Math.min(e.end, dayStart + to * HOUR) })));
  const showNow = now >= top0 && now <= dayStart + to * HOUR;

  return (
    <div className="ruled" style={{ "--hours": to - from } as Vars}>
      {window && (
        <div
          className="ruled-window"
          aria-hidden="true"
          style={{ "--from": Math.max(0, window.startMinute / 60 - from), "--to": Math.min(to - from, window.endMinute / 60 - from) } as Vars}
        />
      )}
      {hours.map((h, i) => (
        <div key={h} className="ruled-hour" style={{ "--i": i } as Vars} aria-hidden="true">
          <span className="ruled-label num">{String(h).padStart(2, "0")}</span>
        </div>
      ))}

      <ol className="slots" aria-label="The day, in order">
        {laned.map((e) => {
          const len = (e.end - e.start) / 60_000;
          const task = e.itemId ? p.task(e.itemId) : null;
          const body = (
            <>
              <span className="slot-title"><span className="hl">{e.title}</span></span>
              <span className="slot-meta num">
                {clock(e.start)}–{clock(e.end)}
                {e.meta ? `, ${e.meta}` : ""}
              </span>
              <span className="vh">, {KIND_WORDS[e.kind]}, {hm(len)}</span>
            </>
          );
          return (
            <li
              key={e.key}
              className={["slot", `slot-${e.kind}`, len < 50 && "is-short", task && taskClass(task)].filter(Boolean).join(" ")}
              style={{ "--top": at(e.start), "--len": Math.max(len / 60, 0.42), "--lane": e.lane, "--lanes": e.lanes } as Vars}
            >
              {task ? (
                <button
                  type="button"
                  className="slot-open tool-target"
                  onClick={() => (e.kind === "draft" && p.tool === "eraser" ? void p.discard() : p.touchTask(task.id))}
                >
                  {body}
                </button>
              ) : (
                body
              )}
            </li>
          );
        })}
      </ol>

      {conflicts.map((c, i) => {
        const s = c.second.start;
        return (
          <div
            key={i}
            className="clash-bracket"
            style={{ "--top": at(s), "--len": c.overlapMs / HOUR } as Vars}
            aria-hidden="true"
          >
            <span>clash, {hm(c.overlapMs / 60_000)}</span>
          </div>
        );
      })}

      {showNow && (
        <div className="now-line" style={{ "--top": at(now) } as Vars}>
          <span className="vh">Now, {clock(now)}</span>
        </div>
      )}
    </div>
  );
}
