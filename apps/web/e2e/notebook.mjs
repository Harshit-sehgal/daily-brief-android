// The notebook's own gestures, in a real browser against e2e/mock-api.mjs: jot, tick,
// highlight, rub out and write back, pencil in and apply, and the task card. Fast (no
// server, no database), so it is the inner loop for UI work; scripts/web-journey.sh
// remains the acceptance run against the real server.
//
//   node e2e/mock-api.mjs &   NEXT_PUBLIC_FIXTURE=1 NEXT_PUBLIC_API_BASE=http://localhost:8097 npx next dev -p 3007 &
//   node e2e/notebook.mjs
import assert from "node:assert/strict";
import * as playwright from "playwright";

// DAILYBRIEF_BROWSER=firefox|webkit runs the same gestures in another engine; WebKit is
// the one an iPhone and an iPad use. Chromium is the default and the one CI runs.
const engineName = process.env.DAILYBRIEF_BROWSER ?? "chromium";
const engine = playwright[engineName];
const { devices } = playwright;

const base = process.env.DAILYBRIEF_WEB_BASE ?? "http://localhost:3007";
const browser = await engine.launch({
  ...(engineName === "chromium" && process.env.DAILYBRIEF_CHROME_PATH ? { executablePath: process.env.DAILYBRIEF_CHROME_PATH } : {}),
  ...(engineName !== "chromium" && process.env.DAILYBRIEF_BROWSER_PATH ? { executablePath: process.env.DAILYBRIEF_BROWSER_PATH } : {}),
});
// Firefox has no mobile emulation; a phone-sized touch window stands in for it there.
const phoneOptions =
  engineName === "firefox"
    ? { viewport: { width: 390, height: 664 }, hasTouch: true }
    : { ...devices["iPhone 14"], defaultBrowserType: undefined };
const problems = [];
const api = process.env.DAILYBRIEF_MOCK_API ?? "http://localhost:8097";
await fetch(`${api}/v1/fixture/reset`, { method: "POST" });

async function open(options, path = "/", signedIn = true) {
  const context = await browser.newContext(options);
  if (signedIn) {
    await context.addInitScript(() => {
      localStorage.setItem("dailybrief.workspaceId", "w1");
      sessionStorage.setItem("dailybrief.csrf", "mock-csrf");
    });
  }
  const page = await context.newPage();
  page.on("pageerror", (e) => problems.push(`${path}: ${e.message}`));
  page.on("console", (m) => m.type() === "error" && problems.push(`${path}: ${m.text()}`));
  await page.goto(base + path, { waitUntil: "networkidle" });
  return page;
}

try {
  // ── a desk ──────────────────────────────────────────────────────────────
  const page = await open({ viewport: { width: 1440, height: 900 } });
  await page.getByRole("heading", { name: "This week" }).waitFor();
  const inbox = page.locator("#inbox");
  const row = (title) => inbox.locator("li", { hasText: title });

  // The calendar is on days other than today.
  await page.getByText("Quarterly planning").waitFor();

  // Jot: one line, Enter, and it is on the page with what was read from it.
  const line = page.getByRole("textbox", { name: "Inbox task title" });
  await line.fill("Call the bank 20m");
  await line.press("Enter");
  await row("Call the bank").getByText("20m").waitFor();
  assert.equal(await line.inputValue(), "", "the line clears for the next jot");
  assert.ok(await line.evaluate((el) => el === document.activeElement), "the pen stays on the line");

  // Tick it off; it stays, struck through. The pen is still on the jot line here, and
  // taking it off must not move the list under the pointer.
  const before = await row("Call the bank").boundingBox();
  await line.blur();
  assert.deepEqual(await row("Call the bank").boundingBox(), before, "the list does not shift when the jot line loses focus");
  await line.focus();
  await page.getByRole("checkbox", { name: "Call the bank, done" }).click();
  await inbox.locator("li.is-done", { hasText: "Call the bank" }).waitFor();

  // A task ticked the instant it is jotted — before its own save has come back — is
  // still ticked, not refused as unknown.
  await line.fill("Quick one");
  await line.press("Enter");
  await page.getByRole("checkbox", { name: "Quick one, done" }).click();
  await inbox.locator("li.is-done", { hasText: "Quick one" }).waitFor();
  await page.waitForLoadState("networkidle");
  assert.equal(await page.locator(".page-error").count(), 0, "no error for an edit made straight after a jot");
  await inbox.locator("li.is-done", { hasText: "Quick one" }).waitFor();
  await page.locator("body").click({ position: { x: 5, y: 5 } });

  // Highlighter (3): one touch marks a line.
  await page.keyboard.press("3");
  await page.getByRole("button", { name: /Highlighter/, pressed: true }).waitFor();
  await row("Renew the domain").getByRole("button", { name: "Renew the domain" }).click();
  await inbox.locator("li.is-highlighted", { hasText: "Renew the domain" }).waitFor();

  // Eraser (4): rubs a line out, and Undo writes it back.
  await page.keyboard.press("4");
  await row("Book train to Pune").getByRole("button", { name: "Book train to Pune" }).click();
  await page.getByText("Rubbed out").waitFor();
  assert.equal(await row("Book train to Pune").count(), 0, "the erased task leaves the inbox");
  await page.getByRole("button", { name: "Undo", exact: true }).click();
  await row("Book train to Pune").waitFor();

  // Pencil (2): drafts the task a place; nothing is saved until Apply.
  await page.keyboard.press("2");
  await row("Renew the domain").getByRole("button", { name: "Renew the domain" }).click();
  await page.getByText(/Pencilled in/).waitFor();
  await page.getByRole("button", { name: "Apply" }).click();
  await page.getByRole("button", { name: "Undo apply" }).waitFor();

  // Pen (Escape): a touch opens the task's card, where everything can be set.
  await page.keyboard.press("Escape");
  await row("Reply to Kiran").getByRole("button", { name: /Reply to Kiran/ }).click();
  const card = page.getByRole("dialog", { name: "Task" });
  await card.getByRole("button", { name: "1h", exact: true }).click();
  await card.getByRole("textbox", { name: "Task title" }).fill("Reply to Kiran");
  await card.getByRole("button", { name: "Close" }).click();
  await inbox.locator("li", { hasText: /^Reply to Kiran1h$/ }).waitFor();

  // The page turns to next week and back; next week has its own calendar.
  await page.getByRole("button", { name: "The week after" }).click();
  await page.getByRole("heading", { name: "Next week" }).waitFor();
  assert.equal(await page.getByText("Quarterly planning").count(), 0, "this week's events are not on next week");
  await page.getByRole("button", { name: "This week" }).click();
  await page.getByRole("heading", { name: "This week" }).waitFor();

  // N opens the jot card from anywhere.
  await page.keyboard.press("n");
  await page.getByRole("dialog", { name: "Jot down" }).getByRole("textbox").waitFor();
  await page.keyboard.press("Escape");

  // The other pages open without complaint.
  for (const [path, heading] of [["/today", "Today"], ["/projects", "Projects"], ["/settings", "Settings"]]) {
    await page.goto(base + path, { waitUntil: "networkidle" });
    await page.getByRole("heading", { name: heading, level: 1 }).waitFor();
  }
  await page.locator("li", { hasText: "Drive" }).getByRole("button", { name: "Connect" }).click(); // Drive
  await page.getByText(/A readable copy/).waitFor();
  await page.context().close();

  // ── a phone ─────────────────────────────────────────────────────────────
  const phone = await open(phoneOptions);
  await phone.getByRole("heading", { name: "This week" }).waitFor();
  await phone.getByRole("button", { name: "Jot down a task" }).tap();
  const jot = phone.getByRole("dialog", { name: "Jot down" }).getByRole("textbox");
  await jot.fill("Buy stamps");
  await jot.press("Enter");
  await phone.keyboard.press("Escape");
  await phone.locator("#inbox li", { hasText: "Buy stamps" }).waitFor();
  const overflow = await phone.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.equal(overflow, 0, "a phone page never scrolls sideways");

  // The notes sheet: a finger with the pen in hand scrolls and draws nothing; a stylus
  // draws; a finger draws once a drawing tool is picked up; the eraser rubs a stroke out.
  await phone.goto(base + "/today", { waitUntil: "networkidle" });
  const sketch = phone.locator("svg.sketch");
  await sketch.scrollIntoViewIfNeeded();
  const stroke = (pointerType) =>
    sketch.evaluate((el, type) => {
      const box = el.getBoundingClientRect();
      const fire = (name, x, y) =>
        el.dispatchEvent(new PointerEvent(name, { bubbles: true, pointerId: 7, pointerType: type, button: 0, clientX: box.left + x, clientY: box.top + y }));
      fire("pointerdown", 40, 40);
      for (let i = 1; i <= 8; i++) fire("pointermove", 40 + i * 12, 40 + i * 4);
      fire("pointerup", 136, 72);
    }, pointerType);
  const strokes = () => sketch.locator("path.stroke").count();
  await stroke("touch");
  assert.equal(await strokes(), 0, "a finger with the pen in hand does not draw");
  await stroke("pen");
  assert.equal(await strokes(), 1, "a stylus draws with the pen in hand");
  await phone.getByRole("button", { name: /in hand/ }).click();
  await phone.getByRole("button", { name: /^Pencil/ }).click();
  await stroke("touch");
  assert.equal(await strokes(), 2, "a finger draws with the pencil in hand");
  await phone.getByRole("button", { name: /in hand/ }).click();
  await phone.getByRole("button", { name: /^Eraser/ }).click();
  await stroke("touch");
  assert.equal(await strokes(), 0, "the eraser rubs out the strokes it crosses");
  await phone.context().close();

  // ── a shortcut or a share from another app lands on the jot card ───────
  const shared = await open({ viewport: { width: 1280, height: 800 } }, "/?jot=1&text=Read%20the%20brief");
  assert.equal(await shared.getByRole("dialog", { name: "Jot down" }).getByRole("textbox").inputValue(), "Read the brief");
  await shared.context().close();

  // ── signed out: the cover, and the pages anyone may read ───────────────
  const cover = await open({ viewport: { width: 1280, height: 800 } }, "/", false);
  await cover.getByRole("button", { name: "Start the journey" }).waitFor();
  for (const [path, heading] of [["/privacy", "Privacy"], ["/terms", "Terms"]]) {
    await cover.goto(base + path, { waitUntil: "networkidle" });
    await cover.getByRole("heading", { name: heading, level: 1 }).waitFor();
  }
  const manifest = await (await cover.request.get(base + "/manifest.webmanifest")).json();
  assert.equal(manifest.display, "standalone");
  assert.ok(manifest.icons.some((i) => i.purpose === "maskable"), "the manifest carries a maskable icon");
  await cover.context().close();

  assert.deepEqual(problems, [], "the browser console stayed clean");
  console.log(`notebook (${engineName}): OK — jot, tick, highlight, rub out, pencil, card, phone, share, cover`);
} finally {
  await browser.close();
}
