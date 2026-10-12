"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { usePlanner } from "@/lib/planner";
import { JotCard } from "./components/jot";
import { Cross, Exclaim } from "./components/marks";
import { PencilCase } from "./components/pencil-case";
import { TaskCard } from "./components/task";

/*
 * The notebook's top margin: the name, and four places to turn to. The same four,
 * in the same order and with the same words, become a bottom bar on a phone, so
 * nothing about where things live changes with the window.
 *
 * There used to be a 248px rail here holding a search box that searched nothing,
 * an Inbox and a Settings that were anchors into the Plan page, and a project list
 * linking to a fragment that did not exist. Each of these is now a page of its own.
 */

type Place = { href: string; label: string; icon: React.ReactNode };

const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" } as const;

const PLACES: Place[] = [
  {
    href: "/today",
    label: "Today",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M12 3.6c4.9-.2 8.5 3.6 8.4 8.3-.1 4.8-3.9 8.4-8.6 8.3-4.6-.1-8.2-3.8-8.1-8.4C3.8 7.2 7.3 3.8 12 3.6z" />
        <path d="M12.1 7.6v4.6l3.1 1.9" />
      </svg>
    ),
  },
  {
    href: "/",
    label: "Week",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M4.2 5.8c5.2-.3 10.4-.2 15.6.1.3 4.6.2 9.2-.1 13.8-5.1.3-10.3.2-15.4-.1-.3-4.6-.3-9.2-.1-13.8z" />
        <path d="M4.4 10.2c5-.2 10.1-.1 15.2.1M8.6 3.6v3.6M15.4 3.6v3.6M9.8 10.4v9.2M14.3 10.4v9.2" />
      </svg>
    ),
  },
  {
    href: "/projects",
    label: "Projects",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M3.8 7.4c0-1 .7-1.7 1.7-1.7h4l2 2.2h7c1 0 1.7.7 1.7 1.7l-.2 8.8c0 1-.7 1.7-1.7 1.7H5.6c-1 0-1.7-.7-1.7-1.7z" />
        <path d="M7.6 13.2h8.8M7.6 16.2h5.4" />
      </svg>
    ),
  },
  {
    href: "/settings",
    label: "Settings",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M4 7.2c5.3-.2 10.6-.1 16 .1M4.1 16.8c5.3.2 10.6.1 15.9-.1" />
        <circle cx="9" cy="7.2" r="2.1" />
        <circle cx="15.2" cy="16.8" r="2.1" />
      </svg>
    ),
  },
];

function PenIcon({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M15.6 4.6c1-1 2.6-1 3.6 0 1 1 1 2.6 0 3.6l-9.9 9.9-4.5 1.1 1.1-4.5z" />
      <path d="M13.8 6.4l3.7 3.7M4.6 20.3c3.2-.4 6.6-.3 9.8.2" />
    </svg>
  );
}

/** Typing into a field, or a dialog already open: N belongs to that, not to the shortcut. */
function busyElsewhere(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  if (el.closest("input, textarea, select, [contenteditable='true']")) return true;
  return Boolean(document.querySelector("dialog[open]"));
}

function isHere(pathname: string, href: string) {
  return href === "/" ? pathname === "/" : pathname.startsWith(href);
}

function Places({ pathname, variant, from = 0, to = PLACES.length }: { pathname: string; variant: "margin" | "bar"; from?: number; to?: number }) {
  return (
    <>
      {PLACES.slice(from, to).map((p) => (
        <Link
          key={p.href}
          href={p.href}
          className={variant === "margin" ? "place" : "tab-place"}
          aria-current={isHere(pathname, p.href) ? "page" : undefined}
        >
          {variant === "bar" && p.icon}
          <span>{p.label}</span>
        </Link>
      ))}
    </>
  );
}

/** A failed action, said once at the top of whichever page you are on, in red pen. */
function ErrorNote() {
  const p = usePlanner();
  if (!p.error) return null;
  return (
    <div className="red-note page-error" role="alert">
      <Exclaim />
      <p>{p.error}</p>
      <button type="button" className="btn-icon" onClick={p.dismissError} aria-label="Dismiss this message">
        <Cross size={16} />
      </button>
    </div>
  );
}

/** No signal: said once, quietly. Jots are kept and sent when it returns. */
function OfflineNote() {
  const p = usePlanner();
  if (p.online && p.waiting === 0) return null;
  return (
    <p className="offline-note" role="status">
      {p.online ? "Sending what you wrote offline…" : p.waiting > 0 ? `Offline. ${p.waiting} jotted, kept to send.` : "Offline. You can still jot."}
    </p>
  );
}

// The tools are out only where there are tasks to use them on.
const TASK_PAGES = ["/", "/today", "/projects"];

export function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname() ?? "/";
  // Before sign-in there is nowhere to turn to, and the cover carries the name,
  // so the margin stays blank rather than offering four pages that cannot open.
  const signedIn = usePlanner().status === "signed-in";
  const [jotting, setJotting] = useState(false);
  const [shared, setShared] = useState("");

  // The home-screen Jot shortcut and text shared from another app both arrive as
  // /?jot=1…: open the card with the pen on the line, and tidy the address.
  useEffect(() => {
    if (!signedIn) return;
    const q = new URLSearchParams(window.location.search);
    const text = [q.get("title"), q.get("text"), q.get("url")].filter(Boolean).join(" ").trim();
    if (!q.has("jot") && !text) return;
    setShared(text);
    setJotting(true);
    window.history.replaceState(null, "", window.location.pathname);
  }, [signedIn]);

  // Jotting is one key away on every page: N opens the card, as a pen is always to hand.
  useEffect(() => {
    if (!signedIn) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "n" && e.key !== "N") return;
      if (e.metaKey || e.ctrlKey || e.altKey || busyElsewhere(e.target)) return;
      e.preventDefault();
      setJotting(true);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [signedIn]);
  return (
    <div className="notebook">
      <a className="skip" href="#page">Skip to the page</a>
      <header className="margin-top">
        {signedIn && (
          <>
            <Link href="/" className="wordmark" aria-label="Daily Brief, this week" translate="no">
              Daily Brief
            </Link>
            <nav className="places" aria-label="Primary">
              <Places pathname={pathname} variant="margin" />
            </nav>
            <button type="button" className="jot-button" onClick={() => setJotting(true)} aria-keyshortcuts="N">
              <PenIcon size={18} />
              Jot
              <kbd aria-hidden="true">N</kbd>
            </button>
          </>
        )}
      </header>

      <main id="page" className="page" tabIndex={-1}>
        {signedIn && <OfflineNote />}
        <ErrorNote />
        {children}
      </main>

      {signedIn && (
        <nav className="tab-bar" aria-label="Primary">
          <Places pathname={pathname} variant="bar" to={2} />
          {/* The pen sits in the middle of the bar, where a thumb rests: one tap to jot. */}
          <button type="button" className="tab-jot" onClick={() => setJotting(true)} aria-label="Jot down a task">
            <span className="tab-jot-nib">
              <PenIcon size={22} />
            </span>
            <span aria-hidden="true">Jot</span>
          </button>
          <Places pathname={pathname} variant="bar" from={2} />
        </nav>
      )}
      {signedIn && TASK_PAGES.includes(pathname) && <PencilCase />}
      {signedIn && (
        <JotCard
          open={jotting}
          initial={shared}
          onClose={() => {
            setJotting(false);
            setShared("");
          }}
        />
      )}
      {signedIn && <TaskCard />}
    </div>
  );
}
