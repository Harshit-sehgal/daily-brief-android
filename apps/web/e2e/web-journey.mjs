import assert from "node:assert/strict";
import { chromium } from "playwright";

const webBase = process.env.DAILYBRIEF_WEB_BASE ?? "http://localhost:3001";
const evidenceDir = process.env.DAILYBRIEF_WEB_EVIDENCE ?? "build/web-journey-evidence";

const browser = await chromium.launch({
  headless: true,
  ...(process.env.DAILYBRIEF_CHROME_PATH ? { executablePath: process.env.DAILYBRIEF_CHROME_PATH } : {}),
});
const context = await browser.newContext();
const page = await context.newPage();

try {
  await page.goto(`${webBase}/`, { waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "Start the journey" }).click();
  await page.getByRole("heading", { name: "This week" }).waitFor();

  await page.getByRole("button", { name: "Connect calendar" }).click();
  await page.getByText("Client stand-up").waitFor();

  // The default working week is Mon-Fri, so on a weekend the planner correctly
  // pushes every block past Today and the rendered assertion at the end would
  // have to skip — evidence half the week is not evidence. Re-day the saved
  // windows onto today and the next four days through the same cookie/CSRF
  // settings API the app itself uses, so Apply must land on Today every run.
  // Stored weekday convention is Calendar numbering: Sunday=1..Saturday=7.
  const apiBase = process.env.DAILYBRIEF_WEB_API_BASE ?? "http://localhost:8091";
  const cookie = (await context.cookies(webBase))
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
    .map((w, i) => ({ ...w, dayOfWeek: days[i] }));
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

  await page.getByRole("button", { name: "Plan my week" }).first().click();
  await page.getByRole("heading", { name: /^Proposal —/ }).waitFor();
  assert.match(await page.getByText(/Nothing is saved until you apply it/).textContent(), /Nothing is saved/);

  await page.getByRole("button", { name: "Apply" }).click();
  await page.getByRole("button", { name: "Undo apply" }).waitFor();

  // Rendered evidence of both surfaces, for the same reason preview.html exists:
  // a source read cannot tell you the shell actually laid out.
  await page.screenshot({ path: `${evidenceDir}/web-journey-plan.png`, fullPage: true });

  await page.goto(`${webBase}/today`, { waitUntil: "domcontentloaded" });
  await page.getByRole("heading", { name: "Today" }).waitFor();
  await page.screenshot({ path: `${evidenceDir}/web-journey-today.png`, fullPage: true });

  // The saved working week now includes today (re-dayed above), so the applied
  // block must render on Today every day of the week.
  await page.getByText("Browser acceptance task").waitFor();

  console.log("web journey: OK — sign-in, calendar, Inbox, plan, apply, and Today rendered");
} catch (error) {
  await page.screenshot({ path: `${evidenceDir}/web-journey-failure.png`, fullPage: true }).catch(() => {});
  throw error;
} finally {
  await browser.close();
}
