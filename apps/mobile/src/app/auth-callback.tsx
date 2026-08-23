import { useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";

import { Palette } from "@/constants/theme";
import { client, saveSession } from "@/lib/api";

/** Inbound leg of the OAuth flow, for the deep link on native and the redirect on web.
 *  The login screen opens the browser; this route only exchanges the code for a session. */
export default function AuthCallbackScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ code?: string | string[]; state?: string | string[] }>();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      const code = Array.isArray(params.code) ? params.code[0] : params.code;
      const state = Array.isArray(params.state) ? params.state[0] : params.state;
      if (!code) {
        setError("No authorization code in the callback URL");
        return;
      }
      if (!state) {
        setError("No OAuth state in the callback URL");
        return;
      }
      const redirectUri = makeRedirectUri();
      try {
        const session = await client.authCallback(code, redirectUri, state);
        await saveSession(session);
        router.replace("/today");
      } catch (e) {
        setError(e instanceof Error ? e.message : "auth exchange failed");
      }
    })();
  }, [params.code, params.state, router]);

  return (
    <View style={styles.center}>
      {error ? <Text style={styles.error}>{error}</Text> : <ActivityIndicator />}
    </View>
  );
}

export function makeRedirectUri(): string {
  if (typeof window !== "undefined" && typeof window.location !== "undefined") {
    return `${window.location.origin}/auth/callback`;
  }
  return "mobile://auth/callback";
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24 },
  error: { color: Palette.deadline, fontSize: 14 },
});
