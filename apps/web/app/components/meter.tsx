/*
 * One bar, a few parts, a 2px paper gap so each part stays countable. The kinds
 * reuse the notebook's ink meanings: "plan" is your own work in ink, "fixed" is
 * commitment amber, "over" is red pen, "free" is the unfilled paper.
 */

export type MeterPart = { kind: "plan" | "fixed" | "free" | "over"; minutes: number };

export function Meter({ parts, label, thin = false }: { parts: MeterPart[]; label: string; thin?: boolean }) {
  const total = parts.reduce((sum, p) => sum + Math.max(0, p.minutes), 0);
  return (
    <div className={thin ? "meter meter-thin" : "meter"} role="img" aria-label={label}>
      {total > 0 &&
        parts
          .filter((p) => p.minutes > 0)
          .map((p, i) => (
            <span key={i} className={`meter-${p.kind}`} style={{ flexGrow: p.minutes, flexBasis: 0 }} />
          ))}
    </div>
  );
}
