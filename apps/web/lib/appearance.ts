/*
 * Ink and paper — a reader's own convenience, kept in their browser only.
 *
 * The six inks are the app's six accents (tokens.css mirrors Color.kt), named the
 * way a pen is. Blue is the web notebook's default because it is the colour a pen
 * writes in; the meaning of the marks does not change with the ink: whatever its
 * colour, a solid stroke is still your own work and a dashed amber one a commitment.
 */

export const INKS = [
  { key: "indigo", name: "Blue" },
  { key: "teal", name: "Teal" },
  { key: "sage", name: "Green" },
  { key: "plum", name: "Violet" },
  { key: "terracotta", name: "Rust" },
  { key: "honey", name: "Amber" },
] as const;

export type InkKey = (typeof INKS)[number]["key"];
export type Paper = "system" | "light" | "dark";

export const DEFAULT_INK: InkKey = "indigo";
const INK_KEY = "dailybrief.ink";
const PAPER_KEY = "dailybrief.paper";

export function readInk(): InkKey {
  try {
    const saved = window.localStorage.getItem(INK_KEY);
    return INKS.some((i) => i.key === saved) ? (saved as InkKey) : DEFAULT_INK;
  } catch {
    return DEFAULT_INK;
  }
}

export function readPaper(): Paper {
  try {
    const saved = window.localStorage.getItem(PAPER_KEY);
    return saved === "light" || saved === "dark" ? saved : "system";
  } catch {
    return "system";
  }
}

export function applyInk(ink: InkKey) {
  document.documentElement.dataset.accent = ink;
  try {
    window.localStorage.setItem(INK_KEY, ink);
  } catch {
    /* private window: the choice holds for this visit */
  }
}

export function applyPaper(paper: Paper) {
  const root = document.documentElement;
  if (paper === "system") delete root.dataset.theme;
  else root.dataset.theme = paper;
  try {
    if (paper === "system") window.localStorage.removeItem(PAPER_KEY);
    else window.localStorage.setItem(PAPER_KEY, paper);
  } catch {
    /* private window: the choice holds for this visit */
  }
}

/** Runs before first paint so a dark-paper reader never sees a white flash. */
export const APPEARANCE_BOOT = `(function(){try{var d=document.documentElement;var i=localStorage.getItem("${INK_KEY}");if(${JSON.stringify(INKS.map((i) => i.key))}.indexOf(i)>-1)d.dataset.accent=i;var p=localStorage.getItem("${PAPER_KEY}");if(p==="light"||p==="dark")d.dataset.theme=p;}catch(e){}})();`;
