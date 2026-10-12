// Screenshots of every page at the three sizes the notebook is designed for, against
// e2e/mock-api.mjs. A design aid: `node e2e/shots.mjs [outDir] [filter]`.
//   DAILYBRIEF_WEB_BASE   default http://localhost:3007
//   DAILYBRIEF_CHROME_PATH  when Playwright's pinned browser build is not installed
import { mkdirSync } from "node:fs";
import { chromium, devices } from "playwright";

const base = process.env.DAILYBRIEF_WEB_BASE ?? "http://localhost:3007";
const out = process.argv[2] ?? "build/shots";
const filter = process.argv[3] ?? "";
mkdirSync(out, { recursive: true });

const SIZES = {
  desk: { viewport: { width: 1440, height: 900 } },
  pad: { viewport: { width: 820, height: 1180 }, hasTouch: true },
  phone: { ...devices["iPhone 14"], defaultBrowserType: undefined },
};
const PAGES = { week: "/", today: "/today", projects: "/projects", settings: "/settings" };

const browser = await chromium.launch({
  ...(process.env.DAILYBRIEF_CHROME_PATH ? { executablePath: process.env.DAILYBRIEF_CHROME_PATH } : {}),
});
for (const [size, options] of Object.entries(SIZES)) {
  for (const scheme of ["light", "dark"]) {
    if (scheme === "dark" && size === "pad") continue;
    const context = await browser.newContext({ ...options, colorScheme: scheme });
    await context.addInitScript(() => {
      localStorage.setItem("dailybrief.workspaceId", "w1");
      sessionStorage.setItem("dailybrief.csrf", "mock-csrf");
    });
    const page = await context.newPage();
    for (const [name, path] of Object.entries(PAGES)) {
      const file = `${size}-${name}${scheme === "dark" ? "-dark" : ""}`;
      if (filter && !file.includes(filter)) continue;
      await page.goto(base + path, { waitUntil: "networkidle" });
      await page.waitForTimeout(400);
      await page.screenshot({ path: `${out}/${file}.png`, fullPage: size !== "desk" });
    }
    await context.close();
  }
}

// States a page is only in after something is done to it.
const STATES = {
  "task-card": async (page) => {
    await page.locator("#inbox li button.entry-open").nth(1).click();
    await page.getByRole("dialog", { name: "Task" }).waitFor();
  },
  "jot-card": async (page, size) => {
    if (size === "phone") await page.getByRole("button", { name: "Jot down a task" }).tap();
    else await page.keyboard.press("n");
    await page.getByRole("dialog", { name: "Jot down" }).getByRole("textbox").fill("Send the invoice 45m fri");
  },
  "tools-open": async (page, size) => {
    if (size === "desk") await page.keyboard.press("3");
    else await page.getByRole("button", { name: /in hand/ }).click();
  },
  proposal: async (page) => {
    await page.getByRole("button", { name: "Plan my week" }).click();
    await page.getByRole("heading", { name: /pencilled in$/ }).waitFor();
  },
  notes: async (page, size) => {
    await page.goto(base + "/today", { waitUntil: "networkidle" });
    await page.getByRole("textbox", { name: "Notes for the day" }).fill("Ask Priya about the venue\nInvoice number is 2031");
    const sketch = page.locator("svg.sketch");
    await sketch.scrollIntoViewIfNeeded();
    const box = await sketch.boundingBox();
    const draw = async (points) => {
      await page.mouse.move(box.x + points[0][0] * box.width, box.y + points[0][1] * box.height);
      await page.mouse.down();
      for (const [x, y] of points.slice(1)) await page.mouse.move(box.x + x * box.width, box.y + y * box.height, { steps: 6 });
      await page.mouse.up();
    };
    if (size === "phone") return; // a finger with the pen scrolls; the sheet shows its hint
    await draw([[0.15, 0.3], [0.2, 0.2], [0.3, 0.18], [0.36, 0.28], [0.3, 0.4], [0.2, 0.42], [0.15, 0.3]]);
    await draw([[0.36, 0.28], [0.6, 0.28], [0.56, 0.24]]);
    await page.getByRole("button", { name: /Pencil/ }).click();
    await draw([[0.62, 0.2], [0.85, 0.2], [0.85, 0.4], [0.62, 0.4], [0.62, 0.2]]);
    await page.getByRole("button", { name: /Highlighter/ }).click();
    await draw([[0.64, 0.3], [0.83, 0.3]]);
  },
  cover: null,
};
for (const [size, options] of Object.entries(SIZES)) {
  if (size === "pad") continue;
  for (const [name, act] of Object.entries(STATES)) {
    const file = `${size}-state-${name}`;
    if (filter && !file.includes(filter)) continue;
    const context = await browser.newContext({ ...options });
    if (act) {
      await context.addInitScript(() => {
        localStorage.setItem("dailybrief.workspaceId", "w1");
        sessionStorage.setItem("dailybrief.csrf", "mock-csrf");
      });
    }
    const page = await context.newPage();
    await page.goto(base + "/", { waitUntil: "networkidle" });
    if (act) await act(page, size);
    await page.waitForTimeout(350);
    await page.screenshot({ path: `${out}/${file}.png` });
    await context.close();
  }
}
await browser.close();
console.log(`shots in ${out}`);
