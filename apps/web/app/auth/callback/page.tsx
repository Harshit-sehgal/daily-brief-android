"use client";

import { useEffect, useState, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { client, saveSession } from "@/lib/api";

function CallbackInner() {
  const params = useSearchParams();
  const [message, setMessage] = useState("Completing sign-in…");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const code = params.get("code");
    if (!code) {
      setError("No code in the callback URL");
      return;
    }
    const redirectUri = window.location.origin + "/auth/callback";
    client
      .authCallback(code, redirectUri)
      .then((s) => {
        saveSession(s);
        window.location.assign("/");
      })
      .catch((e: Error) => {
        setError(e.message);
        setMessage("");
      });
  }, [params]);

  return (
    <div className="card">
      <p>{message}</p>
      {error && <p className="error">{error}</p>}
    </div>
  );
}

export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<div className="card"><p>Completing sign-in…</p></div>}>
      <CallbackInner />
    </Suspense>
  );
}