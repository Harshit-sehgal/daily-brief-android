import * as Device from "expo-device";
import * as Notifications from "expo-notifications";
import { Platform } from "react-native";

import { client } from "./api";

/**
 * The Expo-side half of the push wiring (the server half — tenant-bound registry, provider
 * seam, the Apply trigger — shipped 2026-08-18). One token per install is minted at
 * sign-in, registered on the server, and deregistered at sign-out. The token is kept in
 * memory + module state only: it is device identity, not a secret, and re-registering
 * after a reinstall just upserts the same (workspace, token) row.
 */

let currentPushToken: string | null = null;

async function mintToken(): Promise<string | null> {
  if (Platform.OS === "web" || !Device.isDevice) return null;
  const { status: existing } = await Notifications.getPermissionsAsync();
  let status = existing;
  if (existing !== "granted") {
    const requested = await Notifications.requestPermissionsAsync();
    status = requested.status;
  }
  if (status !== "granted") return null;
  const token = await Notifications.getExpoPushTokenAsync();
  return token.data;
}

/** Mint (if needed) and register the install's push token with the server. */
export async function registerPushToken(): Promise<void> {
  try {
    if (!currentPushToken) currentPushToken = await mintToken();
    if (!currentPushToken) return;
    await client.registerDevice(currentPushToken);
  } catch {
    // A push registry failure must never block sign-in; the apply-time delivery just misses
    // this install until the next registration attempt.
  }
}

/** Deregister the install's token at sign-out, so the workspace stops ringing this phone. */
export async function unregisterPushToken(): Promise<void> {
  try {
    if (!currentPushToken) return;
    await client.unregisterDevice(currentPushToken);
    currentPushToken = null;
  } catch {
    // The registry is tenant-bound and upserted; a stale token is harmless.
  }
}

/**
 * The app's notification handler: applied plans are the one pushed moment, and a cold-start
 * tap into a notification is the same signal — the plan landed. Displayed alerts are enough;
 * no deep link exists yet.
 */
export function installNotificationHandler() {
  if (Platform.OS === "web") return;
  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowBanner: true,
      shouldShowList: true,
      shouldPlaySound: false,
      shouldSetBadge: false,
    }),
  });
}