"use client";

import Link from "next/link";
import { FIXTURE, usePlanner } from "@/lib/planner";

/*
 * The notebook's cover: what it is, and the one way in. Signing in asks Google only
 * who you are; the calendar and Drive are connected later, from inside, each by its
 * own consent.
 */
export function SignIn() {
  const p = usePlanner();
  return (
    <div className="cover-spread">
    <section className="cover" aria-labelledby="cover-title">
      <h1 id="cover-title" className="cover-title">Daily Brief</h1>
      <p className="cover-lede">Jot it down. See your week. Plan around your calendar.</p>
      <div className="actions">
        <button className={FIXTURE ? "btn btn-ink" : "btn btn-google"} onClick={p.signIn} disabled={p.busy !== null}>
          {!FIXTURE && <GoogleG />}
          {p.busy === "sign-in" ? "Opening…" : FIXTURE ? "Start the journey" : "Continue with Google"}
        </button>
      </div>
      <p className="cover-foot">
        <Link href="/privacy">Privacy</Link>
        <Link href="/terms">Terms</Link>
      </p>
    </section>
    <CoverSample />
    </div>
  );
}

/*
 * What the notebook is, shown rather than said: one day of it, with each of the four
 * marks on it — a calendar commitment, a highlighted task, one ticked off, one in
 * pencil. Drawn with the app's own lines, so it cannot drift from the real thing. It is
 * a picture: hidden from assistive technology, which has the sentence above instead.
 */
function CoverSample() {
  const lines = [
    { time: "09:00", kind: "plan", title: "Send the invoice", cls: "is-highlighted", len: "30m" },
    { time: "10:00", kind: "fixed", title: "Client stand-up", cls: "", len: "30m" },
    { time: "11:00", kind: "plan", title: "Rework the homepage", cls: "is-done", len: "2h" },
    { time: "14:00", kind: "draft", title: "Draft the findings", cls: "", len: "1h" },
  ];
  return (
    <div className="cover-sample" aria-hidden="true">
      <div className="day is-today">
        <div className="day-head">
          <div className="day-title">
            <span className="day-num">6</span>
            <span className="day-name">Tuesday</span>
          </div>
        </div>
        <ul className="entries">
          {lines.map((l) => (
            <li key={l.time} className={`entry entry-${l.kind} ${l.cls}`}>
              <span className="entry-time num">{l.time}</span>
              <span className={`mark mark-${l.kind}`} />
              <span className="entry-text">
                <span className="entry-title"><span className="hl">{l.title}</span></span>
              </span>
              <span className="entry-len num">{l.len}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

/** Google's own mark, in its own colours, as its sign-in branding asks. */
function GoogleG() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
      <path fill="#4285F4" d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844c-.209 1.125-.843 2.078-1.796 2.717v2.258h2.908c1.702-1.567 2.684-3.874 2.684-6.615z" />
      <path fill="#34A853" d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332A8.997 8.997 0 0 0 9 18z" />
      <path fill="#FBBC05" d="M3.964 10.71A5.41 5.41 0 0 1 3.682 9c0-.593.102-1.17.282-1.71V4.958H.957A8.996 8.996 0 0 0 0 9c0 1.452.348 2.827.957 4.042l3.007-2.332z" />
      <path fill="#EA4335" d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 0 0 .957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z" />
    </svg>
  );
}

/** Pages render their content only for a known, signed-in reader. While the session
 *  is still being read (the first client render) the page stays blank paper rather
 *  than flashing the cover at someone who is signed in. */
export function SignedIn({ children }: { children: React.ReactNode }) {
  const p = usePlanner();
  if (p.status === "unknown") return <div className="page-blank" aria-busy="true" />;
  if (p.status === "signed-out") return <SignIn />;
  return <>{children}</>;
}
