/*
 * The few marks a person makes in a margin: a tick, a cross, an exclamation, and a
 * circle round a date. (The underline under the current page is a CSS mask in
 * globals.css, so it can take the ink colour without a second element.) Drawn as single strokes in currentColor so the words beside
 * them decide their colour — a mark never carries meaning on its own.
 */

const pen = { fill: "none", stroke: "currentColor", strokeLinecap: "round", strokeLinejoin: "round" } as const;

export function Tick({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" {...pen} strokeWidth={2.4}>
      <path d="M4.5 12.8c1.6 1.4 3 2.9 4.2 4.6 2.6-4.6 6.1-8.6 10.6-11.6" />
    </svg>
  );
}

export function Cross({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" {...pen} strokeWidth={2.4}>
      <path d="M6 5.6c4.2 4.1 8.1 8.5 12.2 12.8M18.1 5.8c-4.4 3.9-8.4 8.2-12 12.6" />
    </svg>
  );
}

export function Exclaim({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" {...pen} strokeWidth={2.4}>
      <path d="M12.4 4.2c-.3 3.8-.5 7.4-.6 10.6" />
      <path d="M11.7 19.2v.1" strokeWidth={3.2} />
    </svg>
  );
}

export function Dots({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" {...pen} strokeWidth={3}>
      <path d="M5.5 12.2v.1M12 11.9v.1M18.5 12.1v.1" />
    </svg>
  );
}

/** The loop a pen draws round a date to say "this one". Stretched to its box. */
export function Circled() {
  return (
    <svg className="circled" viewBox="0 0 100 60" preserveAspectRatio="none" aria-hidden="true" {...pen} strokeWidth={2.2}>
      <path className="circled-stroke" pathLength={1} vectorEffect="non-scaling-stroke" d="M58 6.5C33 3 7 12 5.5 30 4 48 30 56.5 55 55.5 80 54.5 96 44 95 28 94 12 72 3.5 44 6.5" />
    </svg>
  );
}
