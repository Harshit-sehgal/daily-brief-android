// Renders app/icon.svg to the PNG sizes an installed app needs. Run after changing the
// mark: `node e2e/make-icons.mjs` (set DAILYBRIEF_CHROME_PATH if Playwright's browser is missing).
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const svg = readFileSync(new URL("../app/icon.svg", import.meta.url), "utf8");
const targets = [
  ["public/icons/icon-192.png", 192],
  ["public/icons/icon-512.png", 512],
  ["app/apple-icon.png", 180],
];
const browser = await chromium.launch({
  ...(process.env.DAILYBRIEF_CHROME_PATH ? { executablePath: process.env.DAILYBRIEF_CHROME_PATH } : {}),
});
for (const [file, size] of targets) {
  const page = await browser.newPage({ viewport: { width: size, height: size } });
  await page.setContent(`<style>html,body{margin:0}svg{display:block;width:${size}px;height:${size}px}</style>${svg}`);
  await page.screenshot({ path: file });
  await page.close();
}
await browser.close();
console.log("icons written");
