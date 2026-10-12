"use client";

import { useSyncExternalStore } from "react";

/*
 * Window-size questions the stylesheet cannot answer on its own.
 *
 * Most of the narrow layout is CSS. These exist for the few places where a narrow
 * window needs a different *structure*, not a different size: the week as a strip
 * of days with one day open, the project ledger as a list. Rendering only the
 * structure that applies (rather than both, one hidden) keeps every title on the
 * page once. Signed-in pages render on the client only, so the server's answer
 * (false) is never what a narrow-window reader sees.
 */
function useMedia(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const list = window.matchMedia(query);
      list.addEventListener("change", onChange);
      return () => list.removeEventListener("change", onChange);
    },
    () => window.matchMedia(query).matches,
    () => false,
  );
}

/** Phone-sized: the 720px line where the top margin becomes a bottom bar. */
export function useIsPhone(): boolean {
  return useMedia("(max-width: 720px)");
}

/** Too narrow for the week's two-page spread: the 1000px line where it closes. */
export function useIsOneLeaf(): boolean {
  return useMedia("(max-width: 1000px)");
}

/** A mouse or trackpad that can hover: rows show their tools on hover and drag with a
 *  click-and-move. Without one (a finger), a row opens its choices on tap instead. */
export function useCanHover(): boolean {
  return useMedia("(hover: hover) and (pointer: fine)");
}
