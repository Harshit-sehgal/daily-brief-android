"use client";

import { useEffect, useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { client, saveSession } from "@/lib/api";

function CallbackInner() {
  const params = useSearchParams();
  const router = useRouter();
  const [message, setMessage] = useState("Completing sign-in…");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const code = params.get("code");
    const state = params.get("state");
    if (!code) {
      setError("No code in the callback URL");
      return;
    }
    if (!state) {
      setError("No OAuth state in the callback URL");
      return;
    }
    const redirectUri = window.location.origin + "/auth/callback";
    client
      .authCallback(code, redirectUri, state)
      .then((s) => {
        saveSession(s);
        router.replace("/");
      })
      .catch((e: Error) => {
        setError(e.message);
        setMessage("");
      });
  }, [params, router]);

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
