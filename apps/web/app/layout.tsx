import type { Metadata, Viewport } from "next";
import { Kalam, Literata } from "next/font/google";
import "./globals.css";
import { Shell } from "./shell";
import { PlannerProvider } from "@/lib/planner";
import { APPEARANCE_BOOT, DEFAULT_INK } from "@/lib/appearance";
import { Pwa } from "./components/pwa";

/*
 * Two hands on one page. The notebook *prints* its structure — dates, hours, rules,
 * explanations — in Literata, a text serif cut for screens with optical sizes, so
 * 13px meta stays crisp; it reads as a printed diary page rather than as app chrome.
 * Everything that is *yours* — your tasks, your plan, the titles of the pages you
 * keep — is written in Kalam, a felt-pen hand that stays readable at list sizes.
 */
const pen = Kalam({ weight: ["300", "400", "700"], subsets: ["latin"], variable: "--font-kalam", display: "swap" });
const print = Literata({ subsets: ["latin"], axes: ["opsz"], variable: "--font-literata", display: "swap" });

export const metadata: Metadata = {
  // Each page names itself in the tab, so four open tabs are four different pages.
  title: { default: "This week — Daily Brief", template: "%s — Daily Brief" },
  description: "A notebook that plans your week around your calendar. Jot a task down and it finds the time.",
  applicationName: "Daily Brief",
  appleWebApp: { capable: true, title: "Daily Brief", statusBarStyle: "default" },
  formatDetection: { telephone: false },
};

// The paper runs to the edges of the screen, under a phone's status bar and home
// indicator; the bottom bar pads itself by the safe area. The address bar takes the
// paper's colour in both themes.
export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#FDFBF8" },
    { media: "(prefers-color-scheme: dark)", color: "#14110E" },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // data-accent is the ink; the boot script swaps in a reader's saved ink and paper
  // before first paint, which is why <html> may differ from the server's render.
  return (
    <html lang="en" data-accent={DEFAULT_INK} className={`${pen.variable} ${print.variable}`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: APPEARANCE_BOOT }} />
      </head>
      <body>
        <Pwa />
        <PlannerProvider>
          <Shell>{children}</Shell>
        </PlannerProvider>
      </body>
    </html>
  );
}
