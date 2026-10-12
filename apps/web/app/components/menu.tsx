"use client";

import { useEffect, useId, useRef, useState } from "react";

/*
 * A button that opens a short list of choices — a day to place a task on, an
 * estimate to give it. On a phone the list rises as a sheet above the bottom bar
 * (the same `.more-menu` shape the More menu uses), so it never hangs off-screen.
 */
export type MenuItem = { key: string; label: React.ReactNode; hint?: string; disabled?: boolean; onSelect: () => void };

export function MenuButton({
  label,
  ariaLabel,
  items,
  className = "btn btn-quiet",
  align = "right",
}: {
  label: React.ReactNode;
  ariaLabel: string;
  items: MenuItem[];
  className?: string;
  align?: "left" | "right";
}) {
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const id = useId();

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (!wrap.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
        trigger.current?.focus();
      }
    };
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div className="menu" ref={wrap}>
      <button
        ref={trigger}
        type="button"
        className={className}
        aria-label={ariaLabel}
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((o) => !o)}
      >
        {label}
      </button>
      {open && (
        <ul className={align === "left" ? "more-menu menu-left" : "more-menu"} id={id}>
          {items.map((item) => (
            <li key={item.key}>
              <button
                type="button"
                disabled={item.disabled}
                onClick={() => {
                  setOpen(false);
                  item.onSelect();
                }}
              >
                <span className="more-label">{item.label}</span>
                {item.hint && <span className="more-hint">{item.hint}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
