"use client";

import { useEffect, useState } from "react";
import { usePlanner, type Planner } from "@/lib/planner";
import { addDays, clock, hm, shortDate, startOfDay, weekday } from "@/lib/format";
import type { Task } from "@/lib/types";
import { IndexCard } from "./index-card";

/*
 * A task, wherever it is written: a bullet you tick and a line you touch.
 *
 * Touching the line does what the tool in hand does — the highlighter marks it, the
 * eraser rubs it out, the pencil drafts it a place in the week — and with the pen it
 * opens the task's card, which holds everything a task can be told. The card is the
 * same on a phone and a desk, and is how every tool's action is reached without one.
 */

export const EFFORTS = [15, 30, 45, 60, 90, 120, 180, 240];

/** The bullet: a dot while open, the journal's cross once done. A real button, always. */
export function TaskBullet({ task }: { task: Task }) {
  const p = usePlanner();
  const done = Boolean(task.completedAt);
  return (
    <button
      type="button"
      className={done ? "tick is-done" : "tick"}
      role="checkbox"
      aria-checked={done}
      aria-label={`${task.title}, done`}
      onClick={() => void p.tick(task.id, !done)}
    >
      <span className={done ? "bullet bullet-done" : "bullet"} aria-hidden="true" />
    </button>
  );
}

export function taskClass(task: Task): string {
  return [task.completedAt && "is-done", task.priority === "high" && !task.completedAt && "is-highlighted"].filter(Boolean).join(" ");
}

/** The days a task can be sent to: the next five that have working hours — one row of
 *  choices. Further out is what dragging to a day, or planning the week, is for. */
export function placeChoices(p: Planner): Array<{ day: number; label: string; open: number }> {
  const tomorrow = addDays(p.todayStart, 1);
  return Array.from({ length: 7 }, (_, i) => addDays(p.todayStart, i))
    .filter((d) => p.capacityMinutes(d) > 0)
    .slice(0, 5)
    .map((d) => ({
      day: d,
      label: d === p.todayStart ? "Today" : d === tomorrow ? "Tomorrow" : `${weekday(d, "short")} ${new Date(d).getDate()}`,
      open: Math.max(0, p.capacityMinutes(d) - p.plannedMinutes(d)),
    }));
}

function dateInputValue(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** The one card every task opens. Rendered once, in the shell. */
export function TaskCard() {
  const p = usePlanner();
  const task = p.openTaskId ? p.task(p.openTaskId) : null;
  return (
    <IndexCard
      open={task !== null}
      onClose={p.closeTask}
      title="Task"
      head={task && <TitleLine key={task.id} task={task} />}
    >
      {task && <TaskCardBody task={task} />}
    </IndexCard>
  );
}

function TitleLine({ task }: { task: Task }) {
  const p = usePlanner();
  const [title, setTitle] = useState(task.title);
  useEffect(() => setTitle(task.title), [task.title]);
  const save = () => {
    const next = title.trim();
    if (next && next !== task.title) void p.editTask(task.id, { title: next });
    else setTitle(task.title);
  };
  return (
    <input
      type="text"
      className="card-title-input"
      name="task-title"
      autoComplete="off"
      value={title}
      aria-label="Task title"
      onChange={(e) => setTitle(e.target.value)}
      onBlur={save}
      onKeyDown={(e) => {
        if (e.key === "Enter") e.currentTarget.blur();
      }}
    />
  );
}

function TaskCardBody({ task }: { task: Task }) {
  const p = usePlanner();
  const done = Boolean(task.completedAt);
  const booked = p.bookedAt(task.id);
  const highlighted = task.priority === "high";
  const others = (p.tasks ?? []).filter((t) => t.boardId === task.boardId && t.id !== task.id && !t.completedAt);
  const waits = p.waitsFor[task.id] ?? "";
  const overdue = !done && task.dueAt != null && task.dueAt < p.now;

  return (
    <>
      <div className="card-toggles">
        <button type="button" className="chip chip-row" aria-pressed={done} onClick={() => void p.tick(task.id, !done)}>
          Done
        </button>
        <button type="button" className="chip chip-row chip-marker" aria-pressed={highlighted} onClick={() => void p.highlight(task.id, !highlighted)}>
          Highlight
        </button>
      </div>

      {!done && (
        <>
          <p className="field-label sheet-gap">{booked !== null ? `Planned ${weekday(booked, "short")} ${clock(booked)}. Move to` : "Pencil in"}</p>
          <div className="choice-row">
            {placeChoices(p).map((c) => (
              <button
                key={c.day}
                type="button"
                className="chip chip-small"
                // A day with no room for it is not offered; the planner would only say no.
                disabled={p.busy !== null || c.open < (task.effortMinutes ?? 1)}
                title={`${hm(c.open)} open`}
                onClick={() => {
                  p.closeTask();
                  void p.placeOnDay(task.id, c.day);
                }}
              >
                {c.label}
              </button>
            ))}
          </div>
        </>
      )}

      <p className="field-label sheet-gap">Takes</p>
      <div className="choice-row">
        {EFFORTS.map((m) => (
          <button
            key={m}
            type="button"
            className="chip chip-small"
            aria-pressed={m === task.effortMinutes}
            onClick={() => void p.setEffort(task.id, m === task.effortMinutes ? null : m)}
          >
            {hm(m)}
          </button>
        ))}
      </div>

      <div className="card-fields sheet-gap">
        <label className="field">
          <span className={overdue ? "field-label overdue" : "field-label"}>{overdue ? "Was due" : "Due"}</span>
          <input
            type="date"
            value={task.dueAt != null ? dateInputValue(task.dueAt) : ""}
            onChange={(e) =>
              // "Due Wednesday" means by the end of Wednesday where the reader is.
              void p.editTask(task.id, { dueAt: e.target.value ? addDays(startOfDay(new Date(`${e.target.value}T12:00`).getTime()), 1) - 1 : null })
            }
          />
        </label>
        {!done && others.length > 0 && (
          <label className="field">
            <span className="field-label">After</span>
            <select value={waits} onChange={(e) => p.setWaitsFor(task.id, e.target.value || null)}>
              <option value="">Anything</option>
              {others.map((o) => (
                <option key={o.id} value={o.id}>{o.title}</option>
              ))}
            </select>
          </label>
        )}
      </div>

      <div className="actions card-foot">
        <button
          type="button"
          className="btn btn-quiet"
          onClick={() => {
            p.closeTask();
            void p.erase(task.id);
          }}
        >
          Rub out
        </button>
      </div>
    </>
  );
}

/** What a list says beside a task: when it is placed, else how long it takes, and when it is due. */
export function TaskFacts({ task }: { task: Task }) {
  const p = usePlanner();
  const booked = p.bookedAt(task.id);
  const done = Boolean(task.completedAt);
  const overdue = !done && task.dueAt != null && task.dueAt < p.now;
  return (
    <>
      {done ? null : booked !== null ? (
        <span className="num">{weekday(booked, "short")} {clock(booked)}</span>
      ) : task.effortMinutes ? (
        <span className="num">{hm(task.effortMinutes)}</span>
      ) : (
        <span className="unsized">no estimate</span>
      )}
      {!done && task.dueAt != null && (
        <span className={overdue ? "overdue" : undefined}>
          {overdue ? "was due" : "due"} {weekday(task.dueAt, "short")} {shortDate(task.dueAt)}
        </span>
      )}
    </>
  );
}
