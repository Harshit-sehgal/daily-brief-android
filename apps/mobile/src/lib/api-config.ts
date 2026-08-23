export function resolveApiBase(
  configuredApiBase: string | undefined,
  nodeEnv: string | undefined,
  platform: string,
): string {
  const configured = configuredApiBase?.trim();
  if (!configured && nodeEnv === "production") {
    throw new Error("EXPO_PUBLIC_API_BASE must be configured for a production mobile build");
  }

  return configured ?? (platform === "android" ? "http://10.0.2.2:8090" : "http://localhost:8090");
}
