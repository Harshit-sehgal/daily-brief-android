// The installed app with no signal: needs a production build (the service worker is not
// registered in development) served against e2e/mock-api.mjs.
//
//   NEXT_PUBLIC_FIXTURE=1 NEXT_PUBLIC_API_BASE=http://localhost:8097 npx next build
//   npx next start -p 3012 &   DAILYBRIEF_WEB_BASE=http://localhost:3012 node e2e/offline.mjs
import assert from "node:assert/strict";
import { chromium } from "playwright";

const base = process.env.DAILYBRIEF_WEB_BASE ?? "http://localhost:3012";
const api = process.env.DAILYBRIEF_MOCK_API ?? "http://localhost:8097";
await fetch(`${api}/v1/fixture/reset`, { method: "POST" });

const browser = await chromium.launch({
  ...(process.env.DAILYBRIEF_CHROME_PATH ? { executablePath: process.env.DAILYBRIEF_CHROME_PATH } : {}),
});
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
await context.addInitScript(() => {
  localStorage.setItem("dailybrief.workspaceId", "w1");
  sessionStorage.setItem("dailybrief.csrf", "mock-csrf");
});
const page = await context.newPage();
try {
  await page.goto(base + "/", { waitUntil: "networkidle" });
  await page.getByText("Renew the domain").waitFor();
  // The worker takes the shell on its first visit.
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
  await page.waitForTimeout(500);

  await context.setOffline(true);
  // Mock API requests are cross-origin and fail like any other when offline.
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.getByRole("heading", { name: "This week" }).waitFor();
  await page.getByText("Renew the domain").waitFor(); // last visit's pages, from the browser
  await page.getByText(/^Offline/).waitFor();

  // A jot written with no signal is on the page at once and kept to send.
  const line = page.getByRole("textbox", { name: "Inbox task title" });
  await line.fill("Written on the train");
  await line.press("Enter");
  await page.locator("#inbox li", { hasText: "Written on the train" }).waitFor();
  await page.getByText(/1 jotted, kept to send/).waitFor();

  // Closing and reopening the notebook, still offline, still shows the unsent jot.
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.locator("#inbox li", { hasText: "Written on the train" }).waitFor();

  // Another page of the app opens offline too.
  await page.goto(base + "/today", { waitUntil: "domcontentloaded" });
  await page.getByRole("heading", { name: "Today", level: 1 }).waitFor();

  // Signal back: the jot is sent without being asked.
  await context.setOffline(false);
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await assert.doesNotReject(async () => {
    for (let i = 0; i < 40; i++) {
      const tasks = await (await fetch(`${api}/v1/tasks`)).json();
      if (tasks.some((t) => t.title === "Written on the train")) return;
      await new Promise((r) => setTimeout(r, 250));
    }
    throw new Error("the offline jot never reached the server");
  });
  console.log("offline: OK — shell and last pages open with no signal, a jot is kept and sent");
} finally {
  await browser.close();
}
