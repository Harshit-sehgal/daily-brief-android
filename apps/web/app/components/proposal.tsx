"use client";

import { usePlanner } from "@/lib/planner";
import { clock, count, weekday } from "@/lib/format";
import { Exclaim, Tick } from "./marks";

/*
 * Analysis never writes. A plan comes back as a draft in pencil: the same blocks
 * are pencilled into the week behind it, and Apply is the only thing that inks
 * them in. Discard rubs the pencil out.
 */
export function ProposalReview({ inlineDays = [] }: { inlineDays?: number[] }) {
  const p = usePlanner();
  const run = p.run;
  if (!run) return null;
  // A task dropped on a day that is on this page is answered inside that day.
  if (p.runOrigin.kind === "drop" && inlineDays.includes(p.runOrigin.day)) return null;
  const proposals = [...run.result.proposals].sort((a, b) => a.startAt - b.startAt);
  const { unplaced } = run.result;
  const warnings = run.result.health.warnings;

  return (
    <section className="draft" aria-labelledby="draft-title">
      <div className="draft-head">
        <h2 id="draft-title">{proposals.length > 0 ? `${count(proposals.length, "block")} pencilled in` : "Nothing fitted"}</h2>
        <div className="actions">
          {proposals.length > 0 && (
            <button className="btn btn-ink" onClick={p.apply} disabled={p.busy !== null}>
              {p.busy === "apply" ? "Applying…" : "Apply"}
            </button>
          )}
          <button className="btn btn-quiet" onClick={p.discard} disabled={p.busy !== null}>
            {proposals.length > 0 ? "Discard" : "Dismiss"}
          </button>
        </div>
      </div>

      {/* One line per reason, not per task: three tasks with no estimate is one
          problem with one fix, and saying it three times buries the titles. */}
      {groupByReason(unplaced).map(([reason, ids]) => (
        <div className="red-note" key={reason}>
          <Exclaim />
          <p>
            <strong>Not placed:</strong>{" "}
            {ids.map((id, i) => (
              <span key={id}>
                {i > 0 && (i === ids.length - 1 ? " and " : ", ")}
                <span className="unplaced-title">{p.taskTitle(id)}</span>
              </span>
            ))}
            . <span className="unplaced-why">{reason}</span>
          </p>
        </div>
      ))}
      {warnings.length > 0 && <p className="print-note">{warnings.join(". ")}.</p>}

      {/* The blocks themselves are pencilled into the days below; the list and the
          planner's reasons are here for anyone who wants them. */}
      {proposals.length > 0 && (
        <details className="draft-why-all">
          <summary>Where, and why</summary>
          <p className="print-note">{run.result.explanation}</p>
          <ol className="draft-list">
            {proposals.map((d) => (
              <li key={`${d.itemId}-${d.startAt}`}>
                <span className="draft-when num">
                  {weekday(d.startAt, "short")} {clock(d.startAt)}–{clock(d.endAt)}
                </span>
                <span className="draft-what">
                  <span className="draft-title">{p.taskTitle(d.itemId)}</span>
                  <span className="draft-why">{d.reason}</span>
                </span>
              </li>
            ))}
          </ol>
        </details>
      )}
    </section>
  );
}

function groupByReason(unplaced: Array<{ itemId: string; reason: string }>): Array<[string, string[]]> {
  const groups = new Map<string, string[]>();
  for (const u of unplaced) groups.set(u.reason, [...(groups.get(u.reason) ?? []), u.itemId]);
  return [...groups.entries()];
}

/** What Apply did, said once, with the way back beside it. */
export function AppliedNote() {
  const p = usePlanner();
  if (!p.applied) return null;
  return (
    <div className="applied" role="status">
      <Tick />
      <span>Applied {count(p.applied.blocks, "block")} to your plan.</span>
      <button className="btn btn-quiet" onClick={p.undo} disabled={p.busy !== null}>
        {p.busy === "undo" ? "Undoing…" : "Undo apply"}
      </button>
    </div>
  );
}
