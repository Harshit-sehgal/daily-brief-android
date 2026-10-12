"use client";

import { useEffect, useState } from "react";
import { usePlanner, type Tool } from "@/lib/planner";
import { Cross } from "./marks";

/*
 * The pencil case: the four things a person holds over a notebook.
 *
 *   pen          writes a task and ticks it off; touching a line opens it
 *   pencil       drafts a task a place in the week — pencil, until you apply it
 *   highlighter  marks what matters, and the planner places it first
 *   eraser       rubs a line out
 *
 * The tool in hand changes what a touch on a task does, exactly as it would on paper,
 * and the pointer becomes the tool. It is an accelerator, never the only way: every
 * action is also on the task's own card, which the pen opens. Keys 1 to 4 pick a tool
 * and Escape puts the pen back in hand.
 */

const s = { fill: "none", stroke: "currentColor", strokeWidth: 1.7, strokeLinecap: "round", strokeLinejoin: "round" } as const;

const TOOLS: Array<{ key: Tool; name: string; does: string; icon: React.ReactNode }> = [
  {
    key: "pen",
    name: "Pen",
    does: "Write and tick off",
    icon: (
      // A fountain nib, so it cannot be mistaken for the pencil beside it.
      <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" {...s}>
        <path d="M12 3.4c2.6 2.9 4.3 5.9 4.9 9.2l-2.6 5.6H9.7l-2.6-5.6c.6-3.300 2.300-6.300 4.900-9.200z" />
        <path d="M12 9.4v5.200M9.200 20.600h5.600" />
        <circle cx="12" cy="8.300" r=".9" />
      </svg>
    ),
  },
  {
    key: "pencil",
    name: "Pencil",
    does: "Draft a place in the week",
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" {...s}>
        <path d="M16.2 3.9l3.9 3.9L9 18.9l-5 1.2 1.2-5z" />
        <path d="M14 6.1l3.9 3.9M5.2 15.1l3.8 3.8" />
        <path d="M4.6 19.4l1.3-.3" strokeWidth={2.6} />
      </svg>
    ),
  },
  {
    key: "highlighter",
    name: "Highlighter",
    does: "Mark what matters",
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" {...s}>
        <path d="M14.2 4.2l5.6 5.6-7.4 7.4-5.6-5.6z" />
        <path d="M6.8 11.6l-1.4 4.2 2.8 2.8 4.2-1.4" />
        <path d="M5.4 15.8L3.6 19.6l3.9-1.7" />
        <path d="M13.5 20.2h6.6" strokeWidth={2.6} />
      </svg>
    ),
  },
  {
    key: "eraser",
    name: "Eraser",
    does: "Rub a line out",
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" {...s}>
        <path d="M13.9 4.6l5.6 5.6c.6.6.6 1.5 0 2.1l-6.6 6.6H8.6l-4-4c-.6-.6-.6-1.5 0-2.1l7.2-8.2c.6-.6 1.5-.6 2.1 0z" />
        <path d="M8.4 9.8l6.3 6.3M12.9 18.9h7.3" />
      </svg>
    ),
  },
];

function typing(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  return Boolean(el?.closest("input, textarea, select, [contenteditable='true']")) || Boolean(document.querySelector("dialog[open]"));
}

export function PencilCase() {
  const p = usePlanner();
  const { tool, setTool } = p;
  // Narrow windows keep only the tool in hand on the page; a tap fans the case open.
  const [open, setOpen] = useState(false);

  useEffect(() => {
    document.documentElement.dataset.tool = tool;
    return () => {
      delete document.documentElement.dataset.tool;
    };
  }, [tool]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey || typing(e.target)) return;
      const picked = TOOLS[Number(e.key) - 1];
      if (picked) setTool(picked.key);
      else if (e.key === "Escape") {
        setTool("pen");
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [setTool]);

  const inHand = TOOLS.find((t) => t.key === tool) ?? TOOLS[0];

  return (
    // A landmark of its own, so a screen-reader user can jump to the tools from anywhere.
    <aside className="tools-landmark" aria-label="Tools">
      <div className={open ? "pencil-case is-open" : "pencil-case"} role="toolbar" aria-label="Tool in hand" aria-orientation="vertical">
        {TOOLS.map((t, i) => (
          <button
            key={t.key}
            type="button"
            aria-pressed={tool === t.key}
            aria-keyshortcuts={String(i + 1)}
            aria-label={`${t.name}: ${t.does.toLowerCase()}`}
            title={`${t.name} · ${t.does} (${i + 1})`}
            className={`tool tool-${t.key}`}
            onClick={() => {
              setTool(t.key);
              setOpen(false);
            }}
          >
            {t.icon}
            <span className="tool-words" aria-hidden="true">
              <span className="tool-name">{t.name}</span>
              <span className="tool-does">{t.does}</span>
            </span>
          </button>
        ))}
        <button
          type="button"
          className={`tool tool-${inHand.key} tool-in-hand`}
          aria-expanded={open}
          aria-label={`${inHand.name} in hand. Change tool`}
          onClick={() => setOpen((o) => !o)}
        >
          {inHand.icon}
        </button>
      </div>

      {p.erased && (
        <div className="rubbed" role="status">
          <span>
            Rubbed out <q>{p.erased.title}</q>
          </span>
          <button type="button" className="btn btn-quiet" onClick={p.restore} disabled={p.busy !== null}>
            Undo
          </button>
          <button type="button" className="btn-icon" onClick={p.forgetErased} aria-label="Dismiss">
            <Cross size={14} />
          </button>
        </div>
      )}
    </aside>
  );
}
