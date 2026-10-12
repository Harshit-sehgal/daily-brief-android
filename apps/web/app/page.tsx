"use client";

import Link from "next/link";
import { usePlanner } from "@/lib/planner";
import { countWords, weekRange } from "@/lib/format";
import { useIsOneLeaf, useIsPhone } from "@/lib/use-phone";
import { SignedIn } from "./components/sign-in";
import { AppliedNote, ProposalReview } from "./components/proposal";
import { MoreMenu } from "./components/tools";
import { StripWeek, TurnWeek, WeekSpread } from "./components/week-spread";

/*
 * The week. One primary action (Plan my week) and the spread. Nothing stands above
 * the days unless it needs a decision: a clash today, or a calendar not yet connected.
 * How full each day is, is written in the day; what is left to place, in the inbox.
 */
export default function WeekPage() {
  return (
    <SignedIn>
      <Week />
    </SignedIn>
  );
}

function Week() {
  const p = usePlanner();
  const clashes = p.clashes.length;
  const phone = useIsPhone();
  // Once the spread cannot open as two pages, the week becomes a strip of days with
  // one open: seven stacked days put five finished ones between a reader and today.
  const oneLeaf = useIsOneLeaf();

  const clash = clashes > 0 && p.weekOffset === 0 && (
    <Link href="/today" className={phone ? "red-link standing" : "red-link date-clash"}>
      {countWords(clashes, "clash", "clashes")} today
    </Link>
  );

  return (
    <>
      <header className="page-head">
        <div className="page-title">
          <h1>{p.weekOffset === 0 ? "This week" : p.weekOffset === 1 ? "Next week" : p.weekOffset === -1 ? "Last week" : p.weekOffset > 0 ? `In ${p.weekOffset} weeks` : `${-p.weekOffset} weeks ago`}</h1>
          <p className="page-date">
            {/* On one leaf the week strip carries the arrows, where the thumb already is. */}
            {!oneLeaf && <TurnWeek by={-1} />}
            {weekRange(p.week.start, undefined, phone ? "short" : "long")}
            {!oneLeaf && <TurnWeek by={1} />}
            {p.weekOffset !== 0 && (
              <button type="button" className="btn btn-quiet btn-inline" onClick={() => p.turnWeek(0)}>
                This week
              </button>
            )}
            {!phone && clash}
          </p>
        </div>
        <div className="page-actions">
          <MoreMenu compact={phone} />
          <button className="btn btn-ink" onClick={() => p.plan("week")} disabled={p.busy !== null || !p.canPlan || p.weekOffset < 0}>
            {p.busy === "plan-week" ? "Planning…" : "Plan my week"}
          </button>
        </div>
      </header>

      {/* A phone's date shares its line with the page's actions, so the clash takes the next. */}
      {phone && clash}
      {p.connections && !p.connections.calendar.connected && (
        <p className="nudge">
          Plan around your meetings.
          <button className="btn btn-quiet" onClick={() => p.connect("calendar")} disabled={p.busy !== null}>
            {p.busy === "connect" ? "Connecting…" : "Connect calendar"}
          </button>
        </p>
      )}

      <AppliedNote />
      <ProposalReview inlineDays={p.week.days} />
      {oneLeaf ? <StripWeek /> : <WeekSpread />}
    </>
  );
}
