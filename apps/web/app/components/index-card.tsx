"use client";

import { useEffect, useId, useRef } from "react";
import { Cross } from "./marks";

/*
 * A focused answer to one question, written on an index card laid over the page.
 * The native <dialog> does the hard parts — focus moves in and is trapped, Escape
 * closes it, and the page behind becomes inert — so nothing here re-implements them.
 */
export function IndexCard({
  open,
  onClose,
  title,
  head,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  /** Replaces the printed title — a task's card is headed by the task's own editable line. */
  head?: React.ReactNode;
  children: React.ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      dialog.showModal();
      // showModal focuses the first control, which on a phone raises the keyboard over
      // a card that was opened to be read. Focus the card; a card that wants the pen
      // on a line (the jot card) takes it back itself.
      dialog.focus();
    }
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="index-card"
      tabIndex={-1}
      aria-labelledby={head ? undefined : titleId}
      aria-label={head ? title : undefined}
      onClose={onClose}
      // A click that lands on the <dialog> itself, not its contents, is on the backdrop.
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="card-inner">
        <div className="card-head">
          {head ?? <h2 id={titleId}>{title}</h2>}
          <button type="button" className="btn-icon" onClick={onClose} aria-label="Close">
            <Cross size={18} />
          </button>
        </div>
        <div className="card-body">{open && children}</div>
      </div>
    </dialog>
  );
}
