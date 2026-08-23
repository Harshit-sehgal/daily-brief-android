import type { Metadata } from "next";
import "./globals.css";
import { Shell } from "./shell";

export const metadata: Metadata = {
  title: "Daily Brief",
  description: "Plan my week, see my day",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // data-accent picks one of the six; terracotta is the app's default. The
  // theme itself is left to the OS unless a data-theme is stamped on.
  return (
    <html lang="en" data-accent="terracotta">
      <body>
        <Shell>{children}</Shell>
      </body>
    </html>
  );
}
