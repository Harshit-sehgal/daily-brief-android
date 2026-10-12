"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, client } from "@/lib/api";
import { addPoint, erase, SHEET_W, strokePath } from "@/lib/ink";
import { usePlanner } from "@/lib/planner";
import type { DayNote, Stroke } from "@/lib/types";

/*
 * The notes sheet beside a day: a few lines to type on, and paper to draw on.
 *
 * This is where the tool in hand is most literally itself. On the sketch, the pen
 * draws in your ink, the pencil in graphite, the highlighter in a broad translucent
 * stroke, and the eraser rubs out whatever stroke it touches. A stylus or a mouse
 * always draws. A finger draws only with the pencil, highlighter or eraser in hand:
 * with the pen (the tool a reader has by default) a finger scrolls the page, so
 * nobody scribbles across their notes while trying to move down them.
 *
 * The sheet is kept in the browser as it changes and sent a moment after the hand
 * stops, so a stroke never waits on the network and nothing is lost to a dropped one.
 */

const SHEET_H = 600;
const ERASER = 14;
const SAVE_AFTER_MS = 900;
// Stroke widths are in screen pixels (non-scaling), so a line is a pen line at any sheet width.
const WIDTH = { pen: 2.2, pencil: 1.4, highlighter: 15 } as const;

const keep = (day: string) => `dailybrief.note.${day}`;

function readKept(day: string): { note: DayNote; unsent: boolean } | null {
  try {
    const raw = window.localStorage.getItem(keep(day));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function writeKept(note: DayNote, unsent: boolean) {
  try {
    window.localStorage.setItem(keep(note.day), JSON.stringify({ note, unsent }));
  } catch {
    /* storage unavailable: the sheet still saves to the server */
  }
}

export function DayNotes({ day }: { day: string }) {
  const p = usePlanner();
  const { tool } = p;
  const [note, setNote] = useState<DayNote>({ day, text: "", strokes: [] });
  const [live, setLive] = useState<Stroke | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const latest = useRef(note);
  const dirty = useRef(false);
  const timer = useRef(0);
  const svg = useRef<SVGSVGElement>(null);

  const send = useCallback(async () => {
    window.clearTimeout(timer.current);
    if (!dirty.current) return;
    const sending = latest.current;
    dirty.current = false;
    try {
      await client.saveNote(sending);
      if (latest.current === sending) writeKept(sending, false);
      setFailed(null);
    } catch (e) {
      dirty.current = true;
      // Offline is not a failure: the sheet is kept here and sent when it can be.
      if (e instanceof ApiError) setFailed(e.message);
    }
  }, []);

  useEffect(() => {
    let current = true;
    const kept = readKept(day);
    if (kept) {
      latest.current = kept.note;
      setNote(kept.note);
      dirty.current = kept.unsent;
    }
    if (kept?.unsent) void send();
    else {
      client
        .note(day)
        .then((fresh) => {
          // What was written while this was loading wins over what the server had.
          if (!current || dirty.current) return;
          latest.current = fresh;
          setNote(fresh);
          writeKept(fresh, false);
        })
        .catch(() => {});
    }
    const flush = () => {
      if (document.visibilityState === "hidden") void send();
    };
    document.addEventListener("visibilitychange", flush);
    window.addEventListener("online", send);
    return () => {
      current = false;
      document.removeEventListener("visibilitychange", flush);
      window.removeEventListener("online", send);
      void send();
    };
  }, [day, send]);

  const change = useCallback(
    (next: DayNote) => {
      latest.current = next;
      dirty.current = true;
      setNote(next);
      writeKept(next, true);
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(send, SAVE_AFTER_MS);
    },
    [send],
  );

  // A stylus must draw even with the pen in hand, where the sheet lets a finger scroll
  // (touch-action: pan-y). CSS cannot tell the two apart, so the scroll a stylus would
  // start is refused here: pointerdown names the pointer just before touchstart arrives.
  const stylus = useRef(false);
  useEffect(() => {
    const el = svg.current;
    if (!el) return;
    const hold = (e: TouchEvent) => {
      const touch = e.changedTouches[0] as Touch & { touchType?: string };
      if (stylus.current || touch?.touchType === "stylus") e.preventDefault();
    };
    el.addEventListener("touchstart", hold, { passive: false });
    return () => el.removeEventListener("touchstart", hold);
  }, []);

  /** A pointer's place on the sheet, in the sheet's own units. */
  function at(e: React.PointerEvent): [number, number] {
    const box = svg.current!.getBoundingClientRect();
    return [((e.clientX - box.left) / box.width) * SHEET_W, ((e.clientY - box.top) / box.height) * SHEET_H];
  }

  const draws = (e: React.PointerEvent) => e.pointerType !== "touch" || tool !== "pen";

  // The stroke under the hand lives in a ref: pointer events arrive faster than React
  // renders, and a handler reading state would act on the stroke as it was a frame ago.
  // `live` is only what is drawn.
  const hand = useRef<{ erasing: boolean; stroke: Stroke | null } | null>(null);

  function rub(x: number, y: number) {
    const left = erase(latest.current.strokes, x, y, ERASER);
    if (left.length !== latest.current.strokes.length) change({ ...latest.current, strokes: left });
  }

  function down(e: React.PointerEvent<SVGSVGElement>) {
    stylus.current = e.pointerType === "pen";
    if (e.button !== 0 || !draws(e)) return;
    e.preventDefault();
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      /* a pointer that cannot be captured still draws while it stays over the sheet */
    }
    const [x, y] = at(e);
    if (tool === "eraser") {
      hand.current = { erasing: true, stroke: null };
      rub(x, y);
    } else {
      hand.current = { erasing: false, stroke: { t: tool, p: [x, y] } };
      setLive(hand.current.stroke);
    }
  }

  function move(e: React.PointerEvent<SVGSVGElement>) {
    const held = hand.current;
    if (!held) return;
    const [x, y] = at(e);
    if (held.erasing) rub(x, y);
    else if (held.stroke) {
      held.stroke = { ...held.stroke, p: addPoint(held.stroke.p, x, y) };
      setLive(held.stroke);
    }
  }

  function up() {
    const held = hand.current;
    hand.current = null;
    setLive(null);
    if (held?.stroke) change({ ...latest.current, strokes: [...latest.current.strokes, held.stroke] });
  }

  const strokes = live ? [...note.strokes, live] : note.strokes;
  const blank = note.strokes.length === 0 && !live;

  return (
    <section className="note notes-sheet" aria-labelledby="notes-title">
      <h2 id="notes-title">Notes</h2>
      <textarea
        className="notes-text"
        value={note.text}
        onChange={(e) => change({ ...latest.current, text: e.target.value })}
        onBlur={send}
        placeholder="Write anything…"
        aria-label="Notes for the day"
        rows={Math.max(3, note.text.split("\n").length + 1)}
      />
      <svg
        ref={svg}
        className={`sketch sketch-${tool}`}
        viewBox={`0 0 ${SHEET_W} ${SHEET_H}`}
        role="img"
        aria-label={blank ? "Sketch area, empty" : `Sketch, ${note.strokes.length} ${note.strokes.length === 1 ? "stroke" : "strokes"}`}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={up}
        onPointerCancel={up}
      >
        {/* Highlighter goes down first, as it does on paper: under the ink, not over it. */}
        {[...strokes.filter((s) => s.t === "highlighter"), ...strokes.filter((s) => s.t !== "highlighter")].map((s, i) => (
          <path key={i} className={`stroke stroke-${s.t}`} d={strokePath(s.p)} strokeWidth={WIDTH[s.t]} vectorEffect="non-scaling-stroke" />
        ))}
        {blank && (
          <text className="sketch-hint" x={SHEET_W / 2} y={SHEET_H / 2} textAnchor="middle">
            Draw here
          </text>
        )}
      </svg>
      {failed && (
        <p className="red-text notes-failed" role="alert">
          Not saved: {failed}
        </p>
      )}
    </section>
  );
}
