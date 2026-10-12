"use client";

import { useEffect, useRef, useState } from "react";
import { usePlanner } from "@/lib/planner";
import { hm, shortDate, weekday } from "@/lib/format";
import { jotLines, parseJot, type Jot, type JotProject } from "@/lib/jot";
import { IndexCard } from "./index-card";
import { Tick } from "./marks";

/*
 * Jotting a task down should take one line and one keystroke. The line understands
 * a few margin habits — "45m", "fri", "12 oct", "#north" — and says underneath what
 * it understood before anything is saved, so the reading is never a surprise. A
 * pasted list becomes one task per line.
 */

/** The estimate a task gets when none is written: what the old effort picker started on. */
export const USUAL_EFFORT = 30;

function withUsual(j: Jot): Jot {
  return { ...j, effortMinutes: j.effortMinutes ?? USUAL_EFFORT };
}

/** What was understood from a jotted line, said in small print under it. Only what was
 *  read is shown: a plain line goes to the inbox at the usual length without comment. */
function JotReading({ jot, into }: { jot: Jot; into?: JotProject }) {
  return (
    <>
      {jot.project && !into && <span className="tok">{jot.project.name}</span>}
      {jot.effortMinutes !== undefined && <span className="tok">{hm(jot.effortMinutes)}</span>}
      {jot.dueAt !== undefined && (
        <span className="tok">
          due {weekday(jot.dueAt, "short")} {shortDate(jot.dueAt)}
        </span>
      )}
      {jot.unknownTag && !into && <span className="tok tok-miss">no project starts {jot.unknownTag}</span>}
    </>
  );
}

export function JotLine({
  label,
  placeholder = "Jot down a task…",
  focusOnMount = false,
  initial = "",
  into,
  onJotted,
}: {
  label: string;
  placeholder?: string;
  focusOnMount?: boolean;
  /** Text to start the line with — what another app shared. */
  initial?: string;
  /** Files every line under this project, whatever #tag it carries. */
  into?: JotProject;
  onJotted?: (jots: Jot[]) => void;
}) {
  const p = usePlanner();
  const [text, setText] = useState(initial);
  const [said, setSaid] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const projects = (p.projects ?? []).filter((x) => !x.isDefault).map((x) => ({ id: x.id, name: x.name }));
  const read = (line: string): Jot => {
    const j = parseJot(line, p.now, into ? [] : projects);
    return into ? { ...j, project: into } : j;
  };
  const jot = read(text);

  useEffect(() => {
    if (!focusOnMount) return;
    // A modal dialog focuses its first control when it opens; take the line back after.
    const id = requestAnimationFrame(() => input.current?.focus());
    return () => cancelAnimationFrame(id);
  }, [focusOnMount]);

  async function save(jots: Jot[]) {
    const n = await p.jot(jots.map(withUsual));
    if (n > 0) {
      onJotted?.(jots);
      setSaid(n === 1 ? null : `Captured ${n} tasks from the list.`);
      // Keep the pen on the line: the next task is usually the next thought.
      input.current?.focus();
      return true;
    }
    return false;
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!jot.title.trim()) return;
    if (await save([jot])) setText("");
  }

  function paste(e: React.ClipboardEvent<HTMLInputElement>) {
    const lines = jotLines(e.clipboardData.getData("text/plain"));
    if (lines.length < 2) return; // one line pastes into the field as usual
    e.preventDefault();
    void save(lines.map(read));
  }

  return (
    <form className="jot" onSubmit={submit}>
      <input
        ref={input}
        type="text"
        className="jot-title"
        placeholder={placeholder}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setSaid(null);
        }}
        onPaste={paste}
        aria-label={label}
        autoComplete="off"
        autoCapitalize="sentences"
        enterKeyHint="done"
      />
      {/* Enter writes the line. The button is for a thumb, and only once there is something to write. */}
      {text.trim() && (
        <button className="btn-icon jot-go" type="submit" aria-label="Capture">
          <Tick size={20} />
        </button>
      )}
      {/* What was read from the line, only while there is something read. The line of
          advice that used to stand here is the placeholder now, so an empty jot line is
          one line and nothing under it. */}
      <p className="jot-read" aria-live="polite">
        {text.trim() ? <JotReading jot={jot} into={into} /> : said ? <span className="jot-said">{said}</span> : null}
      </p>
    </form>
  );
}

/** The quick-capture card: open from anywhere (N, or the pen), jot line after line. */
export function JotCard({ open, onClose, initial = "" }: { open: boolean; onClose: () => void; initial?: string }) {
  const [jotted, setJotted] = useState<Jot[]>([]);
  return (
    <IndexCard
      open={open}
      onClose={() => {
        setJotted([]);
        onClose();
      }}
      title="Jot down"
    >
      <JotLine key={initial} label="Jot a task" placeholder="Call Asha 20m fri…" focusOnMount initial={initial} onJotted={(js) => setJotted((prev) => [...js, ...prev])} />
      {jotted.length > 0 && (
        <ul className="ruled-list jotted" aria-label="Jotted just now">
          {jotted.map((j, i) => (
            <li key={`${j.title}-${i}`} className="line">
              <span className="line-title">{j.title}</span>
              <span className="line-meta">{j.project ? j.project.name : "Inbox"}</span>
            </li>
          ))}
        </ul>
      )}
    </IndexCard>
  );
}
