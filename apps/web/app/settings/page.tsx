"use client";

import { useEffect, useState } from "react";
import { usePlanner } from "@/lib/planner";
import { hm, longDate } from "@/lib/format";
import { WEEK_DAYS, minutesToTime, replaceWeeklyWindow, timeToMinutes, weeklyWindows } from "@/lib/working-schedule";
import { applyInk, applyPaper, INKS, readInk, readPaper, type InkKey, type Paper } from "@/lib/appearance";
import type { WorkSchedule } from "@/lib/types";
import { useIsPhone } from "@/lib/use-phone";
import { SignedIn } from "../components/sign-in";
import { Cross, Tick } from "../components/marks";
import { IndexCard } from "../components/index-card";
import { useInstall } from "../components/pwa";

/*
 * The notebook's front pages: the hours you work, the ink and paper you write
 * with, the calendar it reads, and the account. Each section says its state in
 * its own first line, so the page can be read without touching anything.
 */
export default function SettingsPage() {
  return (
    <SignedIn>
      <Settings />
    </SignedIn>
  );
}

function Settings() {
  return (
    <>
      <header className="page-head">
        <div className="page-title">
          <h1>Settings</h1>
          <p className="page-date">{usePlanner().connections?.email ?? ""}</p>
        </div>
      </header>
      <div className="front-pages">
        <WorkingHours />
        <InkAndPaper />
        <Google />
        <Install />
        <Account />
      </div>
    </>
  );
}

// Monday first, as the week is printed everywhere else in the notebook.
const DAYS_FROM_MONDAY = [...WEEK_DAYS.slice(1), WEEK_DAYS[0]];

function WorkingHours() {
  const p = usePlanner();
  const [draft, setDraft] = useState<WorkSchedule | null>(p.schedule);
  const [saved, setSaved] = useState(false);
  const phone = useIsPhone();
  useEffect(() => setDraft(p.schedule), [p.schedule]);

  if (!draft) return <section className="front"><h2>Working hours</h2><p className="print-note">Opening…</p></section>;
  const dirty = JSON.stringify(draft) !== JSON.stringify(p.schedule);
  const deviceZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const weekly = DAYS_FROM_MONDAY.reduce((sum, d) => {
    const w = weeklyWindows(draft, d.dayOfWeek)[0];
    return sum + (w ? Math.max(0, w.endMinute - w.startMinute) : 0);
  }, 0);

  return (
    <section className="front" aria-labelledby="hours-title">
      <div className="front-head">
        <h2 id="hours-title">Working hours</h2>
        <p className="front-state">
          {hm(weekly)} a week, {draft.timeZoneId.replace(/_/g, " ")}
          {/* Hours are wall-clock times in one zone; offer the device's only when it differs. */}
          {deviceZone && deviceZone !== draft.timeZoneId && (
            <button className="btn btn-quiet btn-inline" type="button" onClick={() => setDraft({ ...draft, timeZoneId: deviceZone })}>
              Use {deviceZone.replace(/_/g, " ")}
            </button>
          )}
        </p>
      </div>
      <div className="hours" role="group" aria-label="Hours for each day">
        {DAYS_FROM_MONDAY.map(({ dayOfWeek, label }) => {
          const w = weeklyWindows(draft, dayOfWeek)[0];
          return (
            <div className="hours-row" key={dayOfWeek}>
              <span className="hours-day">{phone ? label.slice(0, 3) : label}</span>
              <div className="hours-edit">
                {w ? (
                  <>
                    <input
                      type="time"
                      value={minutesToTime(w.startMinute)}
                      onChange={(e) => setDraft(replaceWeeklyWindow(draft, dayOfWeek, timeToMinutes(e.target.value), w.endMinute))}
                      aria-label={`${label} start`}
                    />
                    <span className="hours-to">to</span>
                    <input
                      type="time"
                      value={minutesToTime(w.endMinute)}
                      onChange={(e) => setDraft(replaceWeeklyWindow(draft, dayOfWeek, w.startMinute, timeToMinutes(e.target.value)))}
                      aria-label={`${label} end`}
                    />
                    <button
                      type="button"
                      className="btn-icon"
                      onClick={() => setDraft(replaceWeeklyWindow(draft, dayOfWeek, null, null))}
                      aria-label={`Make ${label} a day off`}
                      title="Make it a day off"
                    >
                      <Cross size={14} />
                    </button>
                  </>
                ) : (
                  <>
                    <span className="hours-off">Day off</span>
                    <button
                      type="button"
                      className="btn btn-quiet"
                      onClick={() => setDraft(replaceWeeklyWindow(draft, dayOfWeek, 540, 1020))}
                    >
                      Add hours
                    </button>
                  </>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Nothing to press until something has changed. */}
      {(dirty || saved) && (
        <div className="actions">
          {dirty && (
            <>
              <button className="btn btn-ink" disabled={p.busy !== null} onClick={async () => setSaved(await p.saveHours(draft))}>
                {p.busy === "save-hours" ? "Saving…" : "Save"}
              </button>
              <button className="btn btn-quiet" onClick={() => setDraft(p.schedule)} disabled={p.busy !== null}>
                Undo
              </button>
            </>
          )}
          {saved && !dirty && (
            <span className="ink-note" role="status"><Tick /> Saved</span>
          )}
        </div>
      )}
    </section>
  );
}

function InkAndPaper() {
  // Read after mount: the choice lives in this browser's storage, which the server
  // cannot see, so the first render shows the defaults and then corrects itself.
  const [ink, setInk] = useState<InkKey>("indigo");
  const [paper, setPaper] = useState<Paper>("system");
  useEffect(() => {
    setInk(readInk());
    setPaper(readPaper());
  }, []);

  return (
    <section className="front" aria-labelledby="ink-title">
      <div className="front-head">
        <h2 id="ink-title">Ink and paper</h2>
      </div>
      <fieldset className="inks">
        <legend className="field-label">Ink</legend>
        {INKS.map((i) => (
          <label key={i.key} className="ink">
            <input
              type="radio"
              name="ink"
              value={i.key}
              checked={ink === i.key}
              onChange={() => {
                setInk(i.key);
                applyInk(i.key);
              }}
            />
            <span className="nib" style={{ background: `var(--hue-${i.key})` }} aria-hidden="true" />
            <span>{i.name}</span>
          </label>
        ))}
      </fieldset>
      <fieldset className="papers">
        <legend className="field-label">Paper</legend>
        {(["system", "light", "dark"] as const).map((k) => (
          <label key={k} className="paper-choice">
            <input
              type="radio"
              name="paper"
              value={k}
              checked={paper === k}
              onChange={() => {
                setPaper(k);
                applyPaper(k);
              }}
            />
            <span>{k === "system" ? "Match this device" : k === "light" ? "Light" : "Dark"}</span>
          </label>
        ))}
      </fieldset>
    </section>
  );
}

/** "just now", "4 min ago", "2 h ago", or the date. */
function ago(ms: number, now: number): string {
  const minutes = Math.max(0, Math.round((now - ms) / 60_000));
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 24 * 60) return `${Math.round(minutes / 60)} h ago`;
  return longDate(ms);
}

function Google() {
  const p = usePlanner();
  const c = p.connections;
  if (!c) return null;
  return (
    <section className="front" aria-labelledby="google-title">
      <div className="front-head">
        <h2 id="google-title">Google</h2>
      </div>
      <ul className="links">
        <li className="link-row">
          <div className="link-text">
            <span className="link-name">Calendar</span>
            <span className="link-state">
              {c.calendar.connected
                ? c.calendar.lastSyncAt
                  ? `Read ${ago(c.calendar.lastSyncAt, p.now)}. Your events are never changed.`
                  : "Connected."
                : "Plans fit around your events. Read only."}
            </span>
          </div>
          {c.calendar.connected ? (
            <button className="btn btn-quiet" onClick={p.syncNow} disabled={p.busy !== null || !p.online}>
              {p.busy === "connect" || p.syncing ? "Reading…" : "Read now"}
            </button>
          ) : (
            <button className="btn" onClick={() => p.connect("calendar")} disabled={p.busy !== null || !p.online}>
              Connect
            </button>
          )}
        </li>
        <li className="link-row">
          <div className="link-text">
            <span className="link-name">Plan in Calendar</span>
            <span className="link-state">
              {c.planCalendar?.connected
                ? c.planCalendar.lastPushAt
                  ? `Your planned work is in a “Daily Brief” calendar, written ${ago(c.planCalendar.lastPushAt, p.now)}.`
                  : "Your planned work is written to a “Daily Brief” calendar."
                : "Shows your planned work in Google Calendar, in a calendar of its own."}
            </span>
          </div>
          {!c.planCalendar?.connected && (
            <button className="btn" onClick={() => p.connect("plan")} disabled={p.busy !== null || !p.online}>
              Connect
            </button>
          )}
        </li>
        <li className="link-row">
          <div className="link-text">
            <span className="link-name">Drive</span>
            <span className="link-state">
              {c.drive.connected
                ? c.drive.lastBackupAt
                  ? `A readable copy, saved ${ago(c.drive.lastBackupAt, p.now)}.`
                  : "A readable copy is kept as you write."
                : "Keeps a readable copy of the notebook in your Drive."}
            </span>
          </div>
          {c.drive.connected ? (
            c.drive.fileUrl && (
              <a className="btn btn-quiet" href={c.drive.fileUrl} target="_blank" rel="noreferrer">
                Open
              </a>
            )
          ) : (
            <button className="btn" onClick={() => p.connect("drive")} disabled={p.busy !== null || !p.online}>
              Connect
            </button>
          )}
        </li>
      </ul>
    </section>
  );
}

/** Shown only where there is something to do: a browser that can install, or an iPhone that needs telling how. */
function Install() {
  const install = useInstall();
  if (install.installed || (!install.canPrompt && !install.ios)) return null;
  return (
    <section className="front" aria-labelledby="install-title">
      <div className="front-head">
        <h2 id="install-title">On this device</h2>
      </div>
      <ul className="links">
        <li className="link-row">
          <div className="link-text">
            <span className="link-name">Install the app</span>
            <span className="link-state">
              {install.canPrompt ? "Opens from your home screen, full screen." : "Tap Share, then Add to Home Screen."}
            </span>
          </div>
          {install.canPrompt && (
            <button className="btn" onClick={install.prompt}>
              Install
            </button>
          )}
        </li>
      </ul>
    </section>
  );
}

function Account() {
  const p = usePlanner();
  const b = p.billing;
  const [deleting, setDeleting] = useState(false);
  const unlimited = b && b.limits.maxProjects === 2147483647;
  return (
    <section className="front" aria-labelledby="account-title">
      <div className="front-head">
        <h2 id="account-title">Account</h2>
        <p className="front-state">
          {b ? (b.status === "trial" ? "Trial" : b.tier === "paid" ? "Paid" : "Free") : ""}
        </p>
      </div>
      {b && (b.status === "trial" || !unlimited) && (
        <p className="print-note">
          {b.status === "trial" && b.trialEndsAt ? `Your trial ends on ${longDate(b.trialEndsAt)}. ` : ""}
          {!unlimited && `${b.usage.projects} of ${b.limits.maxProjects} projects used.`}
        </p>
      )}
      <div className="actions">
        {b?.checkoutUrl && (
          <a className="btn btn-ink" href={b.checkoutUrl} target="_blank" rel="noreferrer">
            Upgrade
          </a>
        )}
        <button className="btn" onClick={p.signOut} disabled={p.busy !== null}>
          {p.busy === "sign-out" ? "Signing out…" : "Sign out"}
        </button>
        <button className="btn btn-quiet" onClick={() => setDeleting(true)} disabled={p.busy !== null}>
          Delete account
        </button>
      </div>
      <IndexCard open={deleting} onClose={() => setDeleting(false)} title="Delete your account?">
        <p className="print-note">
          Every task, plan and project is removed for good, and the notebook’s access to your Google account is withdrawn.
          The copy in your Drive and the “Daily Brief” calendar stay; they are yours.
        </p>
        <div className="actions">
          <button className="btn btn-danger" onClick={p.deleteAccount} disabled={p.busy !== null}>
            {p.busy === "delete-account" ? "Deleting…" : "Delete everything"}
          </button>
          <button className="btn btn-quiet" onClick={() => setDeleting(false)}>
            Keep it
          </button>
        </div>
      </IndexCard>
    </section>
  );
}
