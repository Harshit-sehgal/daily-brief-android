"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { client, savedSession } from "@/lib/api";
import type { Project } from "@/lib/types";

/*
 * The shell every page shares — the same three destinations in the same order
 * whether they render as a rail or, under 720px, as a bottom bar. Identity does
 * not change with window size, which is the whole point of having one component
 * emit both.
 *
 * The rail reads the project list itself rather than taking it from the page.
 * It is one small request and it keeps the shell independent of whichever page
 * is mounted; the alternative was lifting the planner's state above the router.
 */

const ACCENT_KEYS = ["terracotta", "teal", "sage", "plum", "honey", "indigo"] as const;
export type AccentKey = (typeof ACCENT_KEYS)[number];

/** The dot beside a project name cycles the accent pairs, so a project keeps
 *  one colour across every surface without anyone choosing it. */
function projectHue(index: number): string {
  return `var(--hue-${ACCENT_KEYS[index % ACCENT_KEYS.length]})`;
}

function IconToday() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" aria-hidden="true">
      <circle cx="12" cy="12" r="8.5" /><path d="M12 7.5v4.8l3 2" />
    </svg>
  );
}
function IconPlan() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" aria-hidden="true">
      <rect x="3.5" y="5" width="17" height="15" rx="2.5" /><path d="M3.5 9.5h17M8.5 3.5v3M15.5 3.5v3" />
    </svg>
  );
}
function IconInbox() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" aria-hidden="true">
      <path d="M4 7.5h5.5L12 10h8v9.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19.5z" />
    </svg>
  );
}
function IconGear() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" aria-hidden="true">
      <circle cx="12" cy="12" r="3.2" />
      <path d="M19.1 14.6a1.6 1.6 0 0 0 .32 1.77l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.6 1.6 0 0 0-2.71 1.14V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-2.77-1.1l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.6 1.6 0 0 0 3.2 14.3H3a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.3 7.6l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.6 1.6 0 0 0 9.9 3.7V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.77 1.1l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.6 1.6 0 0 0 1.1 2.77H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.47 1z" />
    </svg>
  );
}

type Dest = { href: string; label: string; icon: React.ReactNode; match: (p: string, h: string) => boolean };

const DESTS: Dest[] = [
  { href: "/today", label: "Today", icon: <IconToday />, match: (p) => p === "/today" },
  { href: "/#planner", label: "Plan", icon: <IconPlan />, match: (p) => p === "/" },
  { href: "/#inbox", label: "Inbox", icon: <IconInbox />, match: () => false },
];

function Dests({ pathname, hash, inboxCount }: { pathname: string; hash: string; inboxCount: number | null }) {
  return (
    <>
      {DESTS.map((d) => {
        const active = d.match(pathname, hash);
        return (
          <Link
            key={d.href}
            href={d.href}
            className="dest"
            aria-current={active ? "page" : undefined}
          >
            {d.icon}
            <span className="dest-label">{d.label}</span>
            {d.label === "Inbox" && inboxCount !== null && inboxCount > 0 && (
              <span className="dest-count">{inboxCount}</span>
            )}
          </Link>
        );
      })}
    </>
  );
}

export function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname() ?? "/";
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [inboxCount, setInboxCount] = useState<number | null>(null);

  useEffect(() => {
    if (!savedSession()) return;
    let live = true;
    client
      .listProjects()
      .then((ps) => live && setProjects(ps))
      .catch(() => live && setProjects(null));
    client
      .listTasks()
      .then((ts) => live && setInboxCount(ts.filter((t) => !t.columnId && !t.completedAt && !t.archivedAt).length))
      .catch(() => live && setInboxCount(null));
    return () => {
      live = false;
    };
  }, [pathname]);

  return (
    <>
      <div className="app">
        <nav className="rail" aria-label="Primary">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
                <path d="M12 7v5l3.5 2" />
              </svg>
            </span>
            <span>Daily Brief</span>
          </div>

          <button className="rail-search" type="button">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
              <circle cx="11" cy="11" r="6.5" /><path d="m16 16 4.5 4.5" />
            </svg>
            <span style={{ flex: 1 }}>Search or jump to</span>
            <kbd>⌘K</kbd>
          </button>

          <div className="dests">
            <Dests pathname={pathname} hash="" inboxCount={inboxCount} />
          </div>

          {projects && projects.length > 0 && (
            <>
              <div className="rail-section">
                <span className="lbl">Projects</span>
              </div>
              <div className="tree">
                {projects.map((p, i) => (
                  <Link key={p.id} href="/#project-board" className="tree-row">
                    <span className="tree-dot" style={{ background: projectHue(i) }} aria-hidden="true" />
                    <span className="tree-name">{p.name}</span>
                  </Link>
                ))}
              </div>
            </>
          )}

          <div className="rail-foot">
            <Link href="/#settings" className="dest">
              <IconGear />
              <span className="dest-label">Settings</span>
            </Link>
          </div>
        </nav>

        <div>{children}</div>
      </div>

      {/* The same three destinations, same labels, same order. */}
      <nav className="bbar" aria-label="Primary">
        <Dests pathname={pathname} hash="" inboxCount={inboxCount} />
      </nav>
    </>
  );
}
