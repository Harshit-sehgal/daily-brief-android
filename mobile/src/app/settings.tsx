import { useRouter } from "expo-router";
import { useEffect, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";

import { API_BASE, clearSession, savedSession } from "@/lib/api";

export default function SettingsScreen() {
  const router = useRouter();
  const [workspaceId, setWorkspaceId] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      const s = await savedSession();
      if (!s) {
        router.replace("/");
        return;
      }
      setWorkspaceId(s.workspaceId);
    })();
  }, [router]);

  async function signOut() {
    await clearSession();
    router.replace("/");
  }

  return (
    <View style={styles.body}>
      <Text style={styles.label}>Workspace</Text>
      <Text style={styles.value}>{workspaceId ?? "…"}</Text>
      <Text style={styles.label}>Server</Text>
      <Text style={styles.value}>{API_BASE}</Text>
      <Pressable style={styles.signOut} onPress={signOut}>
        <Text style={styles.signOutText}>Sign out</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  body: { flex: 1, padding: 24, gap: 4 },
  label: { fontSize: 13, opacity: 0.6, marginTop: 16 },
  value: { fontSize: 16, fontFamily: "monospace" },
  signOut: {
    marginTop: 32,
    borderWidth: 1,
    borderColor: "#d93a3a",
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: "center",
    minHeight: 48,
  },
  signOutText: { color: "#d93a3a", fontSize: 15, fontWeight: "600" },
});