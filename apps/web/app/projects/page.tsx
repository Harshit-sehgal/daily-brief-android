"use client";

import { useState } from "react";
import { usePlanner } from "@/lib/planner";
import { addDays, count, hm, weekday } from "@/lib/format";
import { useIsPhone } from "@/lib/use-phone";
import type { PortfolioBlock, Task } from "@/lib/types";
import { SignedIn } from "../components/sign-in";
import { JotLine } from "../components/jot";
import { TaskBullet, TaskFacts, taskClass } from "../components/task";

/*
 * Projects, as a ledger and the list behind it. Each project is one line: its name,
 * how many tasks are open, where its week goes (hours per day as small columns), and
 * anything overdue. Choosing a row opens that project's tasks underneath, where one
 * can be added, ticked, or opened.
 *
 * A phone folds the line into a name with its week beside it and the numbers beneath.
 */
export default function ProjectsPage() {
  return (
    <SignedIn>
      <Projects />
    </SignedIn>
  );
}

const HUES = ["terracotta", "teal", "sage", "plum", "honey", "indigo"] as const;

function Projects() {
  const p = usePlanner();
  const [name, setName] = useState("");
  const rows = p.portfolio?.rows ?? [];
  const hueOf = (projectId: string) => {
    const i = (p.projects ?? []).findIndex((x) => x.id === projectId);
    return `var(--hue-${HUES[Math.max(0, i) % HUES.length]})`;
  };
  const open = rows.reduce((s, r) => s + r.openTasks, 0);
  const daily = new Map(rows.map((r) => [r.projectId, perDay(r.weekBlocks, p.thisWeek.days)]));
  // One scale for every row, set by the busiest project-day, so columns compare across rows.
  const scale = Math.max(120, ...[...daily.values()].flat());
  const phone = useIsPhone();

  return (
    <>
      <header className="page-head">
        <div className="page-title">
          <h1>Projects</h1>
          <p className="page-date">
            {p.portfolio ? `${count(rows.length, "project")}, ${count(open, "open task")}` : ""}
          </p>
        </div>
      </header>

      <section className="ledger-wrap" aria-label="All projects">
        {phone ? (
          <ul className="project-list">
            {rows.map((r) => {
              const selected = r.projectId === p.selectedId;
              return (
                <li key={r.projectId}>
                  <button className="project-row" aria-pressed={selected} onClick={() => p.selectProject(r.projectId)}>
                    <span className="project-row-name">
                      <span className="hue" style={{ background: hueOf(r.projectId) }} aria-hidden="true" />
                      {r.projectName}
                    </span>
                    <WeekStrip minutes={daily.get(r.projectId) ?? []} scale={scale} scheduled={r.scheduledMinutes} bare />
                    <span className="project-row-meta">
                      {r.openTasks} open
                      {r.scheduledMinutes > 0 && `, ${hm(r.scheduledMinutes)} this week`}
                      {r.overdueTasks > 0 && (
                        <>
                          , <strong className="overdue">{r.overdueTasks} overdue</strong>
                        </>
                      )}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        ) : (
          <table className="ledger">
            <thead>
              <tr>
                <th scope="col">Project</th>
                <th scope="col" className="num">Open</th>
                <th scope="col" className="col-week">
                  <span className="week-axis" aria-hidden="true">
                    {p.thisWeek.days.map((d) => (
                      <span key={d} className={d === p.todayStart ? "is-today" : undefined}>{new Date(d).toLocaleDateString([], { weekday: "narrow" })}</span>
                    ))}
                  </span>
                  <span className="vh">This week</span>
                </th>
                <th scope="col" className="num">Overdue</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const selected = r.projectId === p.selectedId;
                return (
                  <tr key={r.projectId} className={selected ? "is-selected" : undefined}>
                    <th scope="row">
                      <button className="ledger-name" aria-pressed={selected} onClick={() => p.selectProject(r.projectId)}>
                        <span className="hue" style={{ background: hueOf(r.projectId) }} aria-hidden="true" />
                        {r.projectName}
                      </button>
                    </th>
                    <td className="num">{r.openTasks}</td>
                    <td className="col-week">
                      <WeekStrip minutes={daily.get(r.projectId) ?? []} scale={scale} scheduled={r.scheduledMinutes} />
                    </td>
                    <td className={r.overdueTasks > 0 ? "num overdue" : "num muted"}>
                      {r.overdueTasks > 0 ? (
                        <strong>
                          {r.overdueTasks}
                          <span className="vh"> {r.overdueTasks === 1 ? "task" : "tasks"} overdue</span>
                        </strong>
                      ) : (
                        "—"
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
        <form
          className="jot jot-wide"
          onSubmit={async (e) => {
            e.preventDefault();
            if (await p.createProject(name)) setName("");
          }}
        >
          <input
            type="text"
            className="jot-title"
            placeholder="Start a new project…"
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-label="New project name"
            name="project-name"
            autoComplete="off"
          />
          {name.trim() && (
            <button className="btn" type="submit" disabled={p.busy !== null}>
              {p.busy === "create-project" ? "Creating…" : "Create"}
            </button>
          )}
        </form>
      </section>

      <BoardLists />
    </>
  );
}

/** Minutes of each local day of the week that a project's blocks cover. */
function perDay(blocks: PortfolioBlock[], days: number[]): number[] {
  return days.map((d) => {
    const next = addDays(d, 1);
    return blocks.reduce((sum, b) => sum + Math.max(0, Math.min(b.endAt, next) - Math.max(b.startAt, d)), 0) / 60_000;
  });
}

/** Where a project's week goes: hours per day as columns on the ledger's shared scale. */
function WeekStrip({ minutes, scale, scheduled, bare = false }: { minutes: number[]; scale: number; scheduled: number; bare?: boolean }) {
  const p = usePlanner();
  const said = p.thisWeek.days
    .map((d, i) => (minutes[i] > 0 ? `${weekday(d, "short")} ${hm(minutes[i])}` : null))
    .filter(Boolean);
  return (
    <div className="strip-wrap">
      <div
        className="strip"
        {...(bare
          ? { "aria-hidden": true }
          : { role: "img", "aria-label": said.length ? `${hm(scheduled)} scheduled this week: ${said.join(", ")}.` : "Nothing scheduled this week." })}
      >
        {p.thisWeek.days.map((d, i) => (
          <span
            key={d}
            className={["strip-day", d === p.todayStart && "is-today", addDays(d, 1) <= p.todayStart && "is-past"].filter(Boolean).join(" ")}
            title={`${weekday(d)}: ${minutes[i] > 0 ? hm(minutes[i]) : "nothing"}`}
          >
            {minutes[i] > 0 && <span className="strip-bar" style={{ height: `${Math.max(8, (minutes[i] / scale) * 100)}%` }} />}
          </span>
        ))}
      </div>
      {!bare && <span className="strip-total num">{scheduled > 0 ? hm(scheduled) : "—"}</span>}
    </div>
  );
}

function BoardLists() {
  const p = usePlanner();
  const board = p.board;

  if (!board) return <div className="page-blank" aria-busy="true" />;
  const unstaged = board.tasks.filter((t) => !t.columnId);
  const all = [
    ...(unstaged.length > 0 ? [{ id: "unstaged", name: "Not staged yet", tasks: unstaged }] : []),
    ...board.stages.map((s) => ({ id: s.id, name: s.name, tasks: board.tasks.filter((t) => t.columnId === s.id) })),
  ];
  // Only stages with something in them are drawn. The web cannot move a task between
  // stages, so three empty columns beside one full one were furniture, not a board.
  const groups = all.filter((g) => g.tasks.length > 0);
  // One group needs no heading: it is simply the project's list.
  const plain = groups.length === 1;

  return (
    <section className="lists" aria-labelledby="lists-title">
      <header className="lists-head">
        <h2 id="lists-title">{board.project.name}</h2>
      </header>
      <JotLine
        key={board.project.id}
        label={`Add a task to ${board.project.name}`}
        placeholder={`Add a task to ${board.project.name}…`}
        into={board.project.isDefault ? undefined : { id: board.project.id, name: board.project.name }}
      />
      <div className={plain ? "stages stages-plain" : "stages"}>
        {groups.map((g) => (
          <section key={g.id} className="stage" aria-labelledby={`stage-${g.id}`}>
            <h3 id={`stage-${g.id}`} className={plain ? "vh" : undefined}>
              {g.name} <span className="stage-count num">{g.tasks.length}</span>
            </h3>
            <ul className="tasks">
              {g.tasks.map((t) => (
                <TaskLine key={t.id} task={t} />
              ))}
            </ul>
          </section>
        ))}
      </div>
    </section>
  );
}

function TaskLine({ task }: { task: Task }) {
  const p = usePlanner();
  const waits = p.waitsFor[task.id];
  return (
    <li className={["task", taskClass(task)].filter(Boolean).join(" ")}>
      <TaskBullet task={task} />
      <button type="button" className="task-open tool-target" onClick={() => p.touchTask(task.id)}>
        <span className="task-title"><span className="hl">{task.title}</span></span>
        <span className="task-meta">
          <TaskFacts task={task} />
          {waits && !task.completedAt && <span>after {p.taskTitle(waits)}</span>}
        </span>
      </button>
    </li>
  );
}
