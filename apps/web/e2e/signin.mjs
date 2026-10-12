// Signing in and connecting, as a real (non-fixture) build does it, with e2e/mock-api.mjs
// standing in for Google: its consent is instant. This is the only run that exercises
// the web client's own OAuth handling — the redirect out, the callback, the return to the
// page the consent was asked from, and the refusal of a callback this browser did not start.
//
//   node e2e/mock-api.mjs &
//   NEXT_PUBLIC_API_BASE=http://localhost:8097 npx next dev -p 3008 &   # no NEXT_PUBLIC_FIXTURE
//   node e2e/signin.mjs
import assert from "node:assert/strict";
import { chromium } from "playwright";

const base = process.env.DAILYBRIEF_WEB_BASE ?? "http://localhost:3008";
const api = process.env.DAILYBRIEF_MOCK_API ?? "http://localhost:8097";
await fetch(`${api}/v1/fixture/reset?scenario=journey`, { method: "POST" });

const browser = await chromium.launch({
  ...(process.env.DAILYBRIEF_CHROME_PATH ? { executablePath: process.env.DAILYBRIEF_CHROME_PATH } : {}),
});
try {
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 800 } })).newPage();

  // A callback nobody here started — a link someone sent — signs nobody in.
  await page.goto(`${base}/auth/callback?code=mock-code&state=mock.signin.attacker`, { waitUntil: "networkidle" });
  await page.getByRole("alert").getByText(/not started in this browser/).waitFor();
  assert.equal(await page.evaluate(() => localStorage.getItem("dailybrief.workspaceId")), null);

  // The cover: one way in, and it asks Google only who you are.
  await page.goto(base + "/", { waitUntil: "networkidle" });
  const start = page.waitForRequest((r) => r.url().includes("/v1/auth/start"));
  await page.getByRole("button", { name: "Continue with Google" }).click();
  assert.ok(!(await start).url().includes("connect="), "sign-in asks for no extra scope");
  await page.getByRole("heading", { name: "This week" }).waitFor();

  // The calendar is not connected yet, and says so with the way to connect it.
  await page.getByText("Plan around your meetings.").waitFor();
  const connect = page.waitForRequest((r) => r.url().includes("/v1/auth/start"));
  await page.getByRole("button", { name: "Connect calendar" }).click();
  assert.ok((await connect).url().includes("connect=calendar"));
  await page.getByText("Client stand-up").waitFor();
  assert.equal(await page.getByText("Plan around your meetings.").count(), 0);

  // Drive, from Settings: consent returns to Settings, not to the week.
  await page.goto(base + "/settings", { waitUntil: "networkidle" });
  await page.locator("li", { hasText: "Drive" }).getByRole("button", { name: "Connect" }).click();
  await page.getByRole("heading", { name: "Settings", level: 1 }).waitFor();
  await page.getByText(/A readable copy/).waitFor();
  assert.equal(new URL(page.url()).pathname, "/settings");

  // The plan in Google Calendar is its own consent too.
  const plan = page.waitForRequest((r) => r.url().includes("/v1/auth/start"));
  await page.locator("li", { hasText: "Plan in Calendar" }).getByRole("button", { name: "Connect" }).click();
  assert.ok((await plan).url().includes("connect=plan"));
  await page.getByText(/planned work is in a/).waitFor();

  // Signing out leaves nothing of the notebook in this browser.
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.getByRole("button", { name: "Continue with Google" }).waitFor();
  const left = await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith("dailybrief.") && !/ink|paper/.test(k)));
  assert.deepEqual(left, [], "sign-out clears the kept pages, jots and notes");

  console.log("signin: OK — foreign callback refused, sign-in, calendar and Drive consents, sign-out");
} finally {
  await fetch(`${api}/v1/fixture/reset?scenario=rich`, { method: "POST" });
  await browser.close();
}
