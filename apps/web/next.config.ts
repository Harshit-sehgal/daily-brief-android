import type { NextConfig } from "next";

// With NEXT_PUBLIC_API_BASE=same-origin the browser calls /v1/… on this site, and this
// server passes those calls on to the planning server at API_PROXY_TARGET. Read at build
// time: rebuild to point somewhere else.
const apiProxyTarget = process.env.API_PROXY_TARGET?.replace(/\/$/, "");

const nextConfig: NextConfig = {
  async rewrites() {
    if (!apiProxyTarget) return [];
    return [
      { source: "/v1/:path*", destination: `${apiProxyTarget}/v1/:path*` },
      { source: "/health/:path*", destination: `${apiProxyTarget}/health/:path*` },
    ];
  },
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          // The notebook is never framed: a framed copy could be dressed up to steal a click.
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
        ],
      },
      {
        // A stale worker would keep serving an old shell; the browser must always ask for it.
        source: "/sw.js",
        headers: [
          { key: "Content-Type", value: "application/javascript; charset=utf-8" },
          { key: "Cache-Control", value: "no-cache, no-store, must-revalidate" },
          { key: "Content-Security-Policy", value: "default-src 'self'; script-src 'self'" },
        ],
      },
    ];
  },
};

export default nextConfig;
