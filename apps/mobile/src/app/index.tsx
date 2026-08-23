import * as WebBrowser from "expo-web-browser";
import { useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { Palette, Space } from "@/constants/palette";
import { ui } from "@/constants/ui";
import { makeRedirectUri } from "@/app/auth-callback";
import { API_BASE, client, savedSession, saveSession } from "@/lib/api";

// The fixture endpoint exists only for local acceptance runs. Production builds must not
// advertise a demo path that the production server deliberately does not expose.
const FIXTURE = process.env.EXPO_PUBLIC_FIXTURE === "1";

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
      const state = new URL(result.url).searchParams.get("state");
      if (!code) {
        setError("No authorization code returned");
        return;
      }
      if (!state) {
        setError("No OAuth state returned");
        return;
      }
      const session = await client.authCallback(code, redirectUri, state);
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
      <View style={ui.centre}>
        <ActivityIndicator color={Palette.accent} />
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.safeArea}>
      <View style={styles.body}>
        <Text style={styles.title}>Daily Brief</Text>
        <Text style={styles.subtitle}>
          Your calendar and your own work in one schedule, so you can see what actually fits.
        </Text>
        <View style={styles.actions}>
          {FIXTURE ? (
            <Pressable
              style={({ pressed }) => [ui.btnPrimary, pressed && styles.pressed]}
              onPress={signUpFixture}
              disabled={busy}
              accessibilityRole="button"
            >
              {busy ? <ActivityIndicator color={Palette.onAccent} /> : <Text style={ui.btnPrimaryText}>Try the demo</Text>}
            </Pressable>
          ) : null}
          <Pressable
            style={({ pressed }) => [ui.btn, pressed && styles.pressed]}
            onPress={signInGoogle}
            disabled={busy}
            accessibilityRole="button"
          >
            <Text style={ui.btnText}>Sign in with Google</Text>
          </Pressable>
        </View>
        {error ? <Text style={ui.error}>{error}</Text> : null}
        <Text style={styles.footnote}>Server: {API_BASE}</Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: Palette.base },
  body: { flex: 1, padding: Space.xl, justifyContent: "center", gap: Space.md },
  // The one place the app speaks at full size; every other screen tops out at 28.
  title: { fontSize: 32, lineHeight: 38, fontWeight: "600", letterSpacing: -0.8, color: Palette.on },
  subtitle: { fontSize: 15, lineHeight: 22, color: Palette.onMuted, maxWidth: 420 },
  actions: { marginTop: Space.xl, gap: Space.md },
  pressed: { opacity: 0.8 },
  footnote: { fontSize: 12, lineHeight: 17, color: Palette.onFaint, marginTop: Space.xl },
});
