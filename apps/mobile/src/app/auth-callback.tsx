import { useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";

import { client, saveSession } from "@/lib/api";

/** Inbound leg of the OAuth flow, for the deep link on native and the redirect on web.
 *  The login screen opens the browser; this route only exchanges the code for a session. */
export default function AuthCallbackScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ code?: string | string[] }>();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      const code = Array.isArray(params.code) ? params.code[0] : params.code;
      if (!code) {
        setError("No authorization code in the callback URL");
        return;
      }
      const redirectUri = makeRedirectUri();
      try {
        const session = await client.authCallback(code, redirectUri);
        await saveSession(session);
        router.replace("/today");
      } catch (e) {
        setError(e instanceof Error ? e.message : "auth exchange failed");
      }
    })();
  }, [params.code, router]);

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
  error: { color: "#d93a3a", fontSize: 14 },
});