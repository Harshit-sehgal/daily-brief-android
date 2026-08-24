import { StyleSheet } from 'react-native';

import { MinimumTouchTarget, Radius, Space } from './palette';

import { C } from './theme';

/**
 * The design language's shared shapes, so four screens cannot each invent their
 * own card, label and row. This is what `globals.css` is for the web client;
 * the values come from the same tokens.
 *
 * Two rules carry the meaning and nothing here may borrow them for anything
 * else:
 *
 *   - `ownPlan` — a solid accent bar — is work you own and the planner may move.
 *   - `ownFixed` — a dashed honey outline — is a commitment it may not.
 *
 * Deadline red means a clash or an overrun, and always appears beside words
 * that say so; it is never the only signal.
 *
 * Type sizes follow the app's scale (Type.kt): 28 / 24 / 20 headline,
 * 17 / 15 / 13 title, 15 / 14 / 12 body, 13 / 12 / 11 label.
 */
export const ui = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.base },
  content: { padding: Space.lg, paddingBottom: 96, gap: Space.sm },
  centre: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: Space.md, padding: Space.xl },

  /** Small uppercase label. Orients a block; never carries the information. */
  eyebrow: {
    fontSize: 11,
    lineHeight: 14,
    fontWeight: '600',
    letterSpacing: 0.4,
    textTransform: 'uppercase',
    color: C.onFaint,
  },
  h1: { fontSize: 28, lineHeight: 34, fontWeight: '600', letterSpacing: -0.6, color: C.on },
  h2: { fontSize: 17, lineHeight: 23, fontWeight: '600', letterSpacing: -0.2, color: C.on },
  body: { fontSize: 15, lineHeight: 22, color: C.on },
  muted: { fontSize: 13, lineHeight: 18, color: C.onMuted },
  /** A total that cannot be complete must say so, and quietly. */
  floorNote: { fontSize: 12, lineHeight: 17, color: C.onMuted },
  error: { fontSize: 14, lineHeight: 20, color: C.deadline },
  empty: { fontSize: 14, lineHeight: 20, color: C.onFaint, paddingVertical: Space.xs },

  card: {
    borderWidth: 1,
    borderColor: C.outlineSoft,
    borderRadius: Radius.block,
    backgroundColor: C.pure,
    padding: 14,
    gap: 6,
  },

  sectionRule: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
    marginTop: Space.lg,
    marginBottom: Space.xs,
  },
  rule: { flex: 1, height: 1, backgroundColor: C.outlineSoft },

  /** One row of the day. */
  row: { flexDirection: 'row', alignItems: 'center', gap: Space.md, minHeight: 52 },
  rowTime: { width: 92, fontSize: 12.5, color: C.onMuted, fontVariant: ['tabular-nums'] },
  rowBody: { flex: 1, minWidth: 0 },
  rowTitle: { fontSize: 14.5, lineHeight: 20, color: C.on },
  rowTitleOwned: { fontSize: 14.5, lineHeight: 20, fontWeight: '500', color: C.on },
  rowMeta: { fontSize: 12, lineHeight: 16, color: C.onMuted, marginTop: 1 },
  rowMetaFixed: { fontSize: 12, lineHeight: 16, color: C.urgent, marginTop: 1 },
  rowTrail: { fontSize: 12, color: C.onMuted, fontVariant: ['tabular-nums'] },

  /** Ownership. Solid accent = yours. Dashed honey = someone else booked it. */
  ownPlan: { width: 3, height: 26, borderRadius: Radius.mark, backgroundColor: C.accent },
  ownFixed: {
    width: 3,
    height: 22,
    borderRadius: Radius.mark,
    borderLeftWidth: 3,
    borderStyle: 'dashed',
    borderColor: C.urgent,
  },

  /** A clash states itself in words, with the overlap named, in place. */
  clash: {
    borderWidth: 1,
    borderColor: C.deadlineEdge,
    borderRadius: Radius.block,
    backgroundColor: C.deadlineWash,
    padding: 13,
    gap: 6,
  },
  clashHead: { fontSize: 14, lineHeight: 20, fontWeight: '600', color: C.deadline },
  clashBody: { fontSize: 13, lineHeight: 19, color: C.deadlineInk },

  /** Capacity: one bar, parts separated by a 2px gap so they stay countable. */
  meter: { flexDirection: 'row', gap: 2, height: 8, marginVertical: 6 },
  meterFixed: { backgroundColor: C.urgent, borderRadius: Radius.mark },
  meterPlan: { backgroundColor: C.accent, borderRadius: Radius.mark },
  meterFree: { flex: 1, backgroundColor: C.surfaceHighest, borderRadius: Radius.mark },
  legend: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.md },
  legendItem: { fontSize: 12.5, color: C.onSoft },

  /* Controls. Visual density may change what fits; it never changes the hit
     area, so every one of these clears MinimumTouchTarget. */
  btn: {
    minHeight: MinimumTouchTarget,
    paddingHorizontal: 14,
    borderRadius: Radius.control,
    borderWidth: 1,
    borderColor: C.outline,
    backgroundColor: C.pure,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnText: { fontSize: 14, fontWeight: '500', color: C.on },
  btnPrimary: {
    minHeight: MinimumTouchTarget,
    paddingHorizontal: 16,
    borderRadius: Radius.control,
    backgroundColor: C.accent,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnPrimaryText: { fontSize: 15, fontWeight: '600', color: C.onAccent },
  btnQuiet: { minHeight: MinimumTouchTarget, paddingHorizontal: 12, justifyContent: 'center' },
  btnQuietText: { fontSize: 14, fontWeight: '500', color: C.accent },

  input: {
    minHeight: MinimumTouchTarget,
    paddingHorizontal: 10,
    borderWidth: 1,
    borderColor: C.outline,
    borderRadius: Radius.control,
    backgroundColor: C.pure,
    fontSize: 15,
    color: C.on,
  },
});

/** Minutes as a person would say them: 30m, 1h, 4h 45m. */
export function hm(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  const h = Math.floor(m / 60);
  if (h === 0) return `${m}m`;
  return m % 60 === 0 ? `${h}h` : `${h}h ${m % 60}m`;
}
