import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import { useEffect } from "react";
import { useColorScheme } from "react-native";

import { savedSession } from "@/lib/api";
import { installNotificationHandler, registerPushToken } from "@/lib/push";

SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const colorScheme = useColorScheme();
  useEffect(() => {
    // The native splash is held until the root navigator exists. Release builds do not
    // auto-hide it after preventAutoHideAsync(), so always release it from the mounted root.
    SplashScreen.hideAsync().catch(() => undefined);
    installNotificationHandler();
    // Register the install's push token once a session exists; failures are silent — a
    // push registry problem must never block the app's ordinary start.
    savedSession().then((session) => {
      if (session) registerPushToken();
    });
  }, []);
  return (
    <ThemeProvider value={colorScheme === "dark" ? DarkTheme : DefaultTheme}>
      <Stack
        screenOptions={{ headerShown: false }}
      >
        <Stack.Screen name="index" />
        <Stack.Screen name="today" options={{ headerShown: true, title: "Today" }} />
        <Stack.Screen name="planner" options={{ headerShown: true, title: "Planner" }} />
        <Stack.Screen name="settings" options={{ headerShown: true, title: "Settings" }} />
        <Stack.Screen name="auth-callback" />
      </Stack>
    </ThemeProvider>
  );
}
