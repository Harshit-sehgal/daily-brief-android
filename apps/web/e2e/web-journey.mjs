import assert from "node:assert/strict";
import { chromium } from "playwright";

const webBase = process.env.DAILYBRIEF_WEB_BASE ?? "http://localhost:3001";
const evidenceDir = process.env.DAILYBRIEF_WEB_EVIDENCE ?? "build/web-journey-evidence";

const browser = await chromium.launch({
  headless: true,
  ...(process.env.DAILYBRIEF_CHROME_PATH ? { executablePath: process.env.DAILYBRIEF_CHROME_PATH } : {}),
});
// A rehearsal of the deployment stack serves https with a certificate Caddy signed
// itself; DAILYBRIEF_INSECURE_TLS=1 (with NODE_TLS_REJECT_UNAUTHORIZED=0 for this
// script's own requests) accepts it. Never needed against a real deployment.
const context = await browser.newContext({ ignoreHTTPSErrors: process.env.DAILYBRIEF_INSECURE_TLS === "1" });
const page = await context.newPage();

try {
  await page.goto(`${webBase}/`, { waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "Start the journey" }).click();
  await page.getByRole("heading", { name: "This week" }).waitFor();

  await page.getByRole("button", { name: "Connect calendar" }).click();
  await page.getByText("Client stand-up").waitFor();

  // The default working week is Mon-Fri, 9 to 5, and a plan never places work in the
  // part of today that has gone — so at a weekend, or in the evening, the planner
  // correctly puts every block on another day and the Today assertion at the end would
  // have to skip. Evidence half the time is not evidence. Re-day the saved windows onto
  // today and the next four days and open them all day, through the same cookie/CSRF
  // settings API the app itself uses, so Apply must land on Today on every run.
  // Stored weekday convention is Calendar numbering: Sunday=1..Saturday=7.
  const apiBase = process.env.DAILYBRIEF_WEB_API_BASE ?? "http://localhost:8091";
  // The session cookie belongs to the API's host, which in a deployment is not the page's.
  const cookie = (await context.cookies(apiBase))
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");
  const csrf = await page.evaluate(() => sessionStorage.getItem("dailybrief.csrf"));
  assert.ok(csrf, "browser session carried no CSRF token");
  const scheduleResponse = await fetch(`${apiBase}/v1/settings/planning`, {
    headers: { Cookie: cookie },
  });
  assert.ok(scheduleResponse.ok, "settings GET failed");
  const schedule = await scheduleResponse.json();
  // JS getDay(): 0=Sunday..6=Saturday — the same Calendar numbering the wire stores.
  const todayDow = new Date().getDay();
  const days = [0, 1, 2, 3, 4].map((k) => ((todayDow + k) % 7) + 1);
  schedule.windows = schedule.windows
    .sort((a, b) => a.rank - b.rank)
    .map((w, i) => ({ ...w, dayOfWeek: days[i], startMinute: 0, endMinute: 1439 }));
  const putResponse = await fetch(`${apiBase}/v1/settings/planning`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Cookie: cookie, "X-DailyBrief-CSRF": csrf },
    body: JSON.stringify(schedule),
  });
  assert.ok(putResponse.ok, `settings PUT failed: ${putResponse.status}`);

  const inbox = page.getByRole("textbox", { name: "Inbox task title" });
  await inbox.fill("Browser acceptance task");
  await page.getByRole("button", { name: "Capture" }).click();
  await page.getByText("Browser acceptance task").waitFor();

  // The settings changed behind the page's back; reload so the plan uses them.
  // Wait for the page to settle: the notebook paints last visit's pages at once and the
  // server's a moment later, and a click aimed in between can land beside its target.
  await page.reload({ waitUntil: "networkidle" });
  await page.getByText("Browser acceptance task").waitFor();

  // Tick, untick, highlight: the notebook's own marks, through the real server.
  await page.getByRole("checkbox", { name: "Browser acceptance task, done" }).click();
  await page.locator("li.is-done", { hasText: "Browser acceptance task" }).waitFor();
  await page.getByRole("checkbox", { name: "Browser acceptance task, done" }).click();
  await page.locator("li:not(.is-done)", { hasText: "Browser acceptance task" }).first().waitFor();
  await page.keyboard.press("3");
  await page.locator("#inbox").getByRole("button", { name: "Browser acceptance task" }).click();
  await page.locator("#inbox li.is-highlighted", { hasText: "Browser acceptance task" }).waitFor();
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "Plan my week" }).first().click();
  await page.getByRole("heading", { name: /pencilled in$/ }).waitFor();

  await page.getByRole("button", { name: "Apply" }).click();
  await page.getByRole("button", { name: "Undo apply" }).waitFor();

  // Rendered evidence of both surfaces, for the same reason preview.html exists:
  // a source read cannot tell you the shell actually laid out.
  await page.screenshot({ path: `${evidenceDir}/web-journey-plan.png`, fullPage: true });

  await page.goto(`${webBase}/today`, { waitUntil: "domcontentloaded" });
  await page.getByRole("heading", { name: "Today" }).waitFor();
  await page.screenshot({ path: `${evidenceDir}/web-journey-today.png`, fullPage: true });

  // The saved working week now includes today (re-dayed above), so the applied block
  // must render on Today every day of the week — except in the day's last minutes, when
  // a 30-minute task no longer fits before midnight and the planner rightly puts it on
  // tomorrow. Then it must be on the week instead (the next one, late on a Sunday).
  const midnight = new Date();
  midnight.setHours(24, 0, 0, 0);
  if (midnight.getTime() - Date.now() > 50 * 60_000) {
    await page.getByText("Browser acceptance task").waitFor();
  } else {
    await page.goto(`${webBase}/`, { waitUntil: "networkidle" });
    const planned = page.locator(".day:not(.inbox) .entry", { hasText: "Browser acceptance task" });
    if ((await planned.count()) === 0) await page.getByRole("button", { name: "The week after" }).click();
    await planned.first().waitFor();
  }

  // Settings reads the connections from the server: the calendar it just fetched, and
  // a Drive copy once connected.
  await page.goto(`${webBase}/settings`, { waitUntil: "domcontentloaded" });
  await page.getByText(/^Read (just now|\d+ min ago)/).waitFor();
  await page.locator("li", { hasText: "Drive" }).getByRole("button", { name: "Connect" }).click();
  await page.getByText(/A readable copy, saved/).waitFor();

  console.log("web journey: OK — sign-in, calendar, Inbox, plan, apply, and Today rendered");
} catch (error) {
  await page.screenshot({ path: `${evidenceDir}/web-journey-failure.png`, fullPage: true }).catch(() => {});
  throw error;
} finally {
  await browser.close();
}
