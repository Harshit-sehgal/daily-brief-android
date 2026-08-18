import { DarkTheme, DefaultTheme, ThemeProvider } from "expo-router";
import { Stack } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import { useColorScheme } from "react-native";

SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const colorScheme = useColorScheme();
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