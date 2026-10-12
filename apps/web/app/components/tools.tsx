"use client";

import { useEffect, useRef, useState } from "react";
import { usePlanner } from "@/lib/planner";
import { clock, count, hm, lapse, shortDate, weekday } from "@/lib/format";
import { IndexCard } from "./index-card";
import { Cross, Dots, Exclaim, Tick } from "./marks";
import { Meter } from "./meter";

/*
 * Everything the week can do that is not the week itself sits behind one "More".
 * Each tool answers one question on its own index card, so the page stays the work
 * and the analysis is a deliberate step away from it. None of them writes: comparing
 * approaches only changes the order the *next* plan uses.
 */

type Tool = "compare" | "capacity" | "baselines";

const TOOLS: Array<{ key: Tool; label: string; hint: string }> = [
  { key: "compare", label: "Compare approaches", hint: "Three ways to order the week" },
  { key: "capacity", label: "Can I take on more?", hint: "Room for another 8 hours a week" },
  { key: "baselines", label: "Baselines", hint: "How the plan has drifted" },
];

export function MoreMenu({ compact = false }: { compact?: boolean }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [tool, setTool] = useState<Tool | null>(null);
  const wrap = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onDown = (e: PointerEvent) => {
      if (!wrap.current?.contains(e.target as Node)) setMenuOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setMenuOpen(false);
        trigger.current?.focus();
      }
    };
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [menuOpen]);

  return (
    <div className="more" ref={wrap}>
      <button
        ref={trigger}
        className={compact ? "btn btn-plain btn-more-compact" : "btn btn-plain"}
        aria-expanded={menuOpen}
        aria-controls="more-menu"
        onClick={() => setMenuOpen((o) => !o)}
      >
        <Dots size={compact ? 22 : 18} />
        {compact ? <span className="vh">More</span> : "More"}
      </button>
      {menuOpen && (
        <ul className="more-menu" id="more-menu">
          {TOOLS.map((t) => (
            <li key={t.key}>
              <button
                type="button"
                onClick={() => {
                  setMenuOpen(false);
                  setTool(t.key);
                }}
              >
                <span className="more-label">{t.label}</span>
                <span className="more-hint">{t.hint}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <IndexCard open={tool === "compare"} onClose={() => setTool(null)} title="Compare approaches">
        <CompareCard />
      </IndexCard>
      <IndexCard open={tool === "capacity"} onClose={() => setTool(null)} title="Can I take on more?">
        <CapacityCard />
      </IndexCard>
      <IndexCard open={tool === "baselines"} onClose={() => setTool(null)} title="Baselines">
        <BaselinesCard />
      </IndexCard>
    </div>
  );
}

function CompareCard() {
  const p = usePlanner();
  const s = p.scenarios;
  return (
    <>
      <p className="print-note">Choosing one sets the order the next plan uses. Nothing is moved now.</p>
      {p.preferredOrder && (
        <p className="ink-note">
          <Tick /> The next plan uses <strong>{p.preferredOrder.name.toLowerCase()}</strong>.
        </p>
      )}
      {!s ? (
        <div className="actions">
          <button className="btn" onClick={p.compare} disabled={p.busy !== null || !p.schedule}>
            {p.busy === "compare" ? "Comparing…" : "Compare"}
          </button>
        </div>
      ) : (
        <>
          <p className="hand-note">{s.spread}</p>
          <ul className="ruled-list">
            {s.scenarios.map((sc) => {
              const placed = new Set(sc.result.proposals.map((x) => x.itemId)).size;
              const chosen = (p.preferredOrder?.key ?? "due") === sc.key;
              return (
                <li key={sc.key} className="option">
                  <div className="option-text">
                    <span className="option-name">{sc.name}</span>
                    <span className="print-note">
                      {count(placed, "task")} placed
                      {sc.result.unplaced.length > 0 && `, ${sc.result.unplaced.length} left over`}. {sc.rationale}
                    </span>
                  </div>
                  {chosen ? (
                    <span className="chosen"><Tick /> In use</span>
                  ) : (
                    <button className="btn" onClick={() => p.chooseOrder(sc.key, sc.name, sc.preferredOrder)}>
                      Use this
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        </>
      )}
    </>
  );
}

const VERDICT = {
  CAN_TAKE: { words: "Yes, it fits", tone: "good", mark: <Tick /> },
  MOVE: { words: "Only by moving work", tone: "warn", mark: <Exclaim /> },
  INCOMPLETE: { words: "Cannot say yet", tone: "warn", mark: <Exclaim /> },
  CANNOT: { words: "No, it does not fit", tone: "bad", mark: <Cross /> },
} as const;

function CapacityCard() {
  const p = usePlanner();
  const c = p.capacity;
  return (
    <>
      <p className="print-note">Is there room for another 8 hours a week, around everything planned?</p>
      {!c ? (
        <div className="actions">
          <button className="btn" onClick={p.checkCapacity} disabled={p.busy !== null || !p.schedule}>
            {p.busy === "capacity" ? "Checking…" : "Check the week"}
          </button>
        </div>
      ) : (
        <>
          <p className={`verdict verdict-${VERDICT[c.verdict].tone}`}>
            {VERDICT[c.verdict].mark}
            {VERDICT[c.verdict].words}
          </p>
          <Meter
            label={`Of ${hm(c.availableMinutes)} available, ${hm(c.plannedMinutes)} is planned and ${hm(c.spareMinutes)} is spare.`}
            parts={[
              { kind: "plan", minutes: c.plannedMinutes },
              { kind: "free", minutes: c.spareMinutes },
            ]}
          />
          <dl className="legend">
            <div><dt>Available</dt><dd>{hm(c.availableMinutes)}</dd></div>
            <div><dt><span className="key key-plan" />Planned</dt><dd>{hm(c.plannedMinutes)}</dd></div>
            <div><dt><span className="key key-free" />Spare</dt><dd>{hm(c.spareMinutes)}</dd></div>
          </dl>
          <p className="hand-note">{c.sentence}</p>
          {c.moves.length > 0 && (
            <ul className="ruled-list">
              {c.moves.map((m) => (
                <li key={m.itemId} className="line">
                  <span className="line-title">{m.title}</span>
                  <span className="line-meta num">{hm(m.unscheduledMinutes)} to move</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {p.unestimated > 0 && (
        <p className="floor-note">
          {count(p.unestimated, "task")} {p.unestimated === 1 ? "states" : "state"} no effort, so this total is a floor.
        </p>
      )}
    </>
  );
}

function BaselinesCard() {
  const p = usePlanner();
  const list = p.baselines;
  return (
    <>
      <p className="print-note">A snapshot of the plan, to compare against later and see what slipped.</p>
      <div className="actions">
        <button className="btn" onClick={p.takeBaseline} disabled={p.busy !== null}>
          {p.busy === "baseline" ? "Taking…" : "Take a baseline now"}
        </button>
      </div>
      {list && list.length > 0 && (
        <ul className="ruled-list">
          {list.map((b) => (
            <li key={b.id} className="line">
              <span className="line-title">
                {weekday(b.createdAt)} {shortDate(b.createdAt)}, {clock(b.createdAt)}
              </span>
              <span className="line-meta">{count(b.taskCount, "task")}, {count(b.blockCount, "block")}</span>
              <button
                className="btn btn-quiet"
                onClick={() => p.compareBaseline(b.id)}
                disabled={p.busy !== null}
                aria-pressed={p.variance?.baselineId === b.id}
              >
                Compare
              </button>
            </li>
          ))}
        </ul>
      )}
      {p.variance && (
        <div className="variance">
          <p className="hand-note">{p.variance.comparison.summary}</p>
          <ul className="ruled-list">
            {p.variance.comparison.rows.map((row) => {
              const drift = row.driftMinutes ?? 0;
              return (
                <li key={row.itemId} className="line">
                  <span className="line-title">{row.title}</span>
                  <span className={drift > 0 ? "line-meta drift-late" : "line-meta"}>
                    {row.addedSinceBaseline
                      ? "scheduled since"
                      : row.removedSinceBaseline
                        ? "no longer scheduled"
                        : drift === 0
                          ? "on time"
                          : `${lapse(drift)} ${drift > 0 ? "later" : "earlier"}`}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </>
  );
}
