"use client";

import Link from "next/link";
import { useEffect, useRef, useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { claimOAuthState, client, saveSession } from "@/lib/api";

function CallbackInner() {
  const params = useSearchParams();
  const router = useRouter();
  const [message, setMessage] = useState("Opening your notebook…");
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    const code = params.get("code");
    const state = params.get("state");
    // The reader pressed Cancel on Google's screen: nothing went wrong, go back.
    if (params.get("error") === "access_denied") {
      router.replace("/");
      return;
    }
    if (!code) {
      setError("No code in the callback URL");
      return;
    }
    if (!state) {
      setError("No OAuth state in the callback URL");
      return;
    }
    // A code is good once, and so is the claim below; React runs an effect twice in
    // development, and the second run must not undo the first.
    if (started.current) return;
    started.current = true;
    // Only a sign-in this browser itself started may finish here.
    const returnTo = claimOAuthState(state);
    if (returnTo === null) {
      setError("This sign-in was not started in this browser, or it was started twice");
      setMessage("");
      return;
    }
    const redirectUri = window.location.origin + "/auth/callback";
    client
      .authCallback(code, redirectUri, state)
      .then((s) => {
        saveSession(s);
        // A connect made from Settings returns there; a sign-in opens the week. A full
        // load, not a client-side turn of the page: the notebook reads who is signed in
        // when it opens, and a soft navigation left it showing the cover to someone
        // who had just signed in.
        window.location.replace(returnTo);
      })
      .catch((e: Error) => {
        setError(e.message);
        setMessage("");
      });
  }, [params, router]);

  return (
    <section className="cover" aria-live="polite">
      <h1 className="cover-title">Daily Brief</h1>
      {message && <p className="cover-lede">{message}</p>}
      {error && (
        <p className="red-text" role="alert">
          Sign-in did not finish: {error}. <Link href="/">Try again from the start</Link>.
        </p>
      )}
    </section>
  );
}

export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<section className="cover"><p className="cover-lede">Opening your notebook…</p></section>}>
      <CallbackInner />
    </Suspense>
  );
}
