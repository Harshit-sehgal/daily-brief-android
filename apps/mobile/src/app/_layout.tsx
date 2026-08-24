import { DarkTheme, DefaultTheme, Tabs, ThemeProvider } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import { useEffect } from "react";
import { ColorValue, Text, useColorScheme } from "react-native";

import { C } from "@/constants/theme";
import { savedSession } from "@/lib/api";
import { installNotificationHandler, registerPushToken } from "@/lib/push";

SplashScreen.preventAutoHideAsync();

function TabIcon({ label, color }: { label: string; color: ColorValue }) {
  return <Text style={{ fontSize: 18, lineHeight: 18, color }}>{label}</Text>;
}

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
      <Tabs
        screenOptions={{
          headerShown: true,
          tabBarActiveTintColor: C.accent,
          tabBarInactiveTintColor: C.onFaint,
          tabBarStyle: { backgroundColor: C.pure, borderTopColor: C.outlineSoft },
          headerStyle: { backgroundColor: C.base },
          headerTintColor: C.on,
        }}
      >
        <Tabs.Screen
          name="index"
          options={{ href: null, headerShown: false, title: "Login", tabBarStyle: { display: "none" } }}
        />
        <Tabs.Screen
          name="today"
          options={{ title: "Today", tabBarIcon: ({ color }) => <TabIcon label="◐" color={color} /> }}
        />
        <Tabs.Screen
          name="planner"
          options={{ title: "Planner", tabBarIcon: ({ color }) => <TabIcon label="≡" color={color} /> }}
        />
        <Tabs.Screen
          name="settings"
          options={{ title: "Settings", tabBarIcon: ({ color }) => <TabIcon label="⚙" color={color} /> }}
        />
        <Tabs.Screen
          name="auth-callback"
          options={{ href: null, headerShown: false, title: "Auth", tabBarStyle: { display: "none" } }}
        />
      </Tabs>
    </ThemeProvider>
  );
}
