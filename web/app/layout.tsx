import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "Daily Brief",
  description: "Plan my week, see my day",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <nav className="nav">
          <span className="nav-title">Daily Brief</span>
          <Link href="/">Planner</Link>
          <Link href="/today">Today</Link>
        </nav>
        <main className="main">{children}</main>
      </body>
    </html>
  );
}