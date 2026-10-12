"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { longDate } from "@/lib/format";

/*
 * Carrying a task to a day, like moving a sticky note across a planner page.
 *
 * Built on pointer events rather than HTML drag-and-drop, which phones do not give
 * a finger: a mouse picks a row up after a few pixels of movement; a finger picks it
 * up after a short press, so an ordinary swipe still scrolls the page. While a row
 * is held the page scrolls itself near the top and bottom edges, Escape puts it
 * back, and anything marked `data-drop-day` underneath the pointer is the target.
 *
 * Dragging is never the only way: every row also has a "Place on…" choice
 * (WCAG 2.5.7), which calls the same `place` as a drop.
 */

export type Carried = { id: string; title: string };
type Drag = { item: Carried; x: number; y: number; over: number | null; touch: boolean };

type Ctx = {
  drag: Drag | null;
  /** The day a drop or a "Place on…" choice last asked for, until its answer is on the page. */
  placing: number | null;
  /** Starts watching a press for a carry. `fromControl` lets a row's own title button
   *  (a phone's tap target) start one; other controls inside a row never do. */
  begin: (e: React.PointerEvent, item: Carried, fromControl?: boolean) => void;
  place: (taskId: string, day: number) => void;
  /** True for a moment after a drag ends, so the release is not also read as a tap. */
  justDropped: () => boolean;
};

const DragContext = createContext<Ctx | null>(null);

export function useCarry(): Ctx | null {
  return useContext(DragContext);
}

const LONG_PRESS_MS = 380;
const EDGE = 96;

/** Keeps the slip on screen at its own width: a finger's slip is centred over it, a
 *  mouse's sits to the right of the pointer until the edge pushes it back. */
function ghostLeft(drag: Drag): number {
  const half = 150;
  if (drag.touch) return Math.min(Math.max(drag.x, half + 8), window.innerWidth - half - 8);
  return Math.min(drag.x, window.innerWidth - 2 * half - 24);
}

export function CarryToDay({ onPlace, children }: { onPlace: (taskId: string, day: number) => Promise<void> | void; children: React.ReactNode }) {
  const [drag, setDrag] = useState<Drag | null>(null);
  const [placing, setPlacing] = useState<number | null>(null);
  const lastDrop = useRef(0);
  const onPlaceRef = useRef(onPlace);
  useEffect(() => {
    onPlaceRef.current = onPlace;
  }, [onPlace]);

  const place = useCallback((taskId: string, day: number) => {
    setPlacing(day);
    Promise.resolve(onPlaceRef.current(taskId, day)).finally(() => setPlacing(null));
  }, []);

  const begin = useCallback(
    (e: React.PointerEvent, item: Carried, fromControl = false) => {
      if (e.button !== 0) return;
      // Controls inside a row (its menus) keep their own clicks.
      if (!fromControl && (e.target as HTMLElement).closest("button, a, input, select, .menu")) return;
      const touch = e.pointerType !== "mouse";
      const sx = e.clientX;
      const sy = e.clientY;
      let x = sx;
      let y = sy;
      let active = false;
      let over: number | null = null;
      let frame = 0;

      const hit = () => {
        const el = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-drop-day]");
        over = el ? Number(el.dataset.dropDay) : null;
      };
      const show = () => setDrag({ item, x, y, over, touch });
      const scroll = () => {
        frame = requestAnimationFrame(() => {
          const bottom = window.innerHeight - EDGE - 40; // above a phone's bottom bar
          const dy = y < EDGE ? -Math.ceil((EDGE - y) / 5) : y > bottom ? Math.ceil((y - bottom) / 5) : 0;
          if (dy !== 0) {
            window.scrollBy(0, dy);
            hit();
            show();
          }
          scroll();
        });
      };
      const activate = () => {
        active = true;
        document.body.classList.add("is-carrying");
        navigator.vibrate?.(12);
        hit();
        show();
        scroll();
      };
      const timer = touch ? window.setTimeout(activate, LONG_PRESS_MS) : 0;

      const move = (ev: PointerEvent) => {
        x = ev.clientX;
        y = ev.clientY;
        if (!active) {
          const moved = Math.hypot(x - sx, y - sy);
          if (touch && moved > 10) finish(false); // a swipe: let the page scroll
          else if (!touch && moved > 5) activate();
          return;
        }
        hit();
        show();
      };
      // Once a finger has picked something up, its movement carries, not scrolls.
      const holdPage = (ev: TouchEvent) => {
        if (active) ev.preventDefault();
      };
      const onKey = (ev: KeyboardEvent) => {
        if (ev.key === "Escape") finish(false);
      };
      const up = () => finish(true);
      const cancel = () => finish(false);

      function finish(drop: boolean) {
        window.clearTimeout(timer);
        cancelAnimationFrame(frame);
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
        window.removeEventListener("pointercancel", cancel);
        window.removeEventListener("touchmove", holdPage);
        window.removeEventListener("keydown", onKey);
        document.body.classList.remove("is-carrying");
        if (!active) return;
        lastDrop.current = Date.now();
        setDrag(null);
        if (drop && over !== null) place(item.id, over);
      }

      window.addEventListener("pointermove", move);
      window.addEventListener("pointerup", up);
      window.addEventListener("pointercancel", cancel);
      window.addEventListener("touchmove", holdPage, { passive: false });
      window.addEventListener("keydown", onKey);
    },
    [place],
  );

  const justDropped = useCallback(() => Date.now() - lastDrop.current < 450, []);

  return (
    <DragContext.Provider value={{ drag, placing, begin, place, justDropped }}>
      {children}
      {drag &&
        createPortal(
          // Under a finger the note rides above the fingertip, where it can be seen.
          <div className={drag.touch ? "carry-ghost is-touch" : "carry-ghost"} style={{ left: ghostLeft(drag), top: drag.y }} aria-hidden="true">
            <span className="carry-title">{drag.item.title}</span>
            <span className="carry-hint">{drag.over !== null ? `Pencil it in on ${longDate(drag.over)}` : "Drop it on a day"}</span>
          </div>,
          document.body,
        )}
    </DragContext.Provider>
  );
}
