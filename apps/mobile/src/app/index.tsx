import * as WebBrowser from "expo-web-browser";
import { useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { makeRedirectUri } from "@/app/auth-callback";
import { API_BASE, client, savedSession, saveSession } from "@/lib/api";

export default function LoginScreen() {
  const router = useRouter();
  const [checking, setChecking] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      if (await savedSession()) {
        router.replace("/today");
        return;
      }
      setChecking(false);
    })();
  }, [router]);

  async function signUpFixture() {
    setBusy(true);
    setError(null);
    try {
      const session = await client.signup();
      await saveSession(session);
      router.replace("/today");
    } catch (e) {
      setError(e instanceof Error ? e.message : "signup failed");
    } finally {
      setBusy(false);
    }
  }

  async function signInGoogle() {
    setBusy(true);
    setError(null);
    try {
      const redirectUri = makeRedirectUri();
      const { url } = await client.authStart(redirectUri);
      if (typeof window !== "undefined" && typeof window.location !== "undefined") {
        window.location.href = url;
        return;
      }
      const result = await WebBrowser.openAuthSessionAsync(url, redirectUri);
      if (result.type !== "success" || !result.url) {
        setError("Sign-in was cancelled");
        return;
      }
      const code = new URL(result.url).searchParams.get("code");
      if (!code) {
        setError("No authorization code returned");
        return;
      }
      const session = await client.authCallback(code, redirectUri);
      await saveSession(session);
      router.replace("/today");
    } catch (e) {
      setError(e instanceof Error ? e.message : "sign-in failed");
    } finally {
      setBusy(false);
    }
  }

  if (checking) {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.safeArea}>
      <View style={styles.body}>
        <Text style={styles.title}>Daily Brief</Text>
        <Text style={styles.subtitle}>Plan your week around your calendar.</Text>
        <View style={styles.actions}>
          <Pressable
            style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
            onPress={signUpFixture}
            disabled={busy}
          >
            {busy ? <ActivityIndicator color="#ffffff" /> : <Text style={styles.buttonText}>Try the demo</Text>}
          </Pressable>
          <Pressable
            style={({ pressed }) => [styles.secondary, pressed && styles.buttonPressed]}
            onPress={signInGoogle}
            disabled={busy}
          >
            <Text style={styles.secondaryText}>Sign in with Google</Text>
          </Pressable>
        </View>
        {error ? <Text style={styles.error}>{error}</Text> : null}
        <Text style={styles.footnote}>Server: {API_BASE}</Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center" },
  safeArea: { flex: 1 },
  body: { flex: 1, padding: 24, justifyContent: "center", gap: 12 },
  title: { fontSize: 32, fontWeight: "700" },
  subtitle: { fontSize: 16, opacity: 0.7 },
  actions: { marginTop: 24, gap: 12 },
  button: {
    backgroundColor: "#3c87f7",
    borderRadius: 12,
    paddingVertical: 14,
    paddingHorizontal: 24,
    alignItems: "center",
    minHeight: 48,
  },
  buttonPressed: { opacity: 0.8 },
  buttonText: { color: "#ffffff", fontSize: 16, fontWeight: "600" },
  secondary: {
    borderRadius: 12,
    borderWidth: 1,
    borderColor: "#c8c8d0",
    paddingVertical: 14,
    paddingHorizontal: 24,
    alignItems: "center",
    minHeight: 48,
  },
  secondaryText: { fontSize: 16, fontWeight: "600" },
  error: { color: "#d93a3a", fontSize: 14 },
  footnote: { fontSize: 12, opacity: 0.5, marginTop: 24 },
});