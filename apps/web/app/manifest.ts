import type { MetadataRoute } from "next";

/*
 * What makes the notebook installable: on a phone, a tablet or a desktop it opens in
 * its own window from the home screen. The Jot shortcut and the share target both lead
 * to the same place — the jot card, open, with the pen on the line — so writing a
 * thing down is one press from outside the app as well as inside it.
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    id: "/",
    name: "Daily Brief",
    short_name: "Daily Brief",
    description: "A notebook that plans your week around your calendar.",
    start_url: "/",
    scope: "/",
    display: "standalone",
    orientation: "any",
    background_color: "#FDFBF8",
    theme_color: "#FDFBF8",
    categories: ["productivity"],
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      // The mark sits inside the middle four-fifths, so a platform's mask never clips it.
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
    shortcuts: [
      { name: "Jot a task", short_name: "Jot", url: "/?jot=1", icons: [{ src: "/icons/icon-192.png", sizes: "192x192" }] },
      { name: "Today", url: "/today", icons: [{ src: "/icons/icon-192.png", sizes: "192x192" }] },
    ],
    share_target: { action: "/", method: "GET", params: { title: "title", text: "text", url: "url" } },
  };
}
