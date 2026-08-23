import { describe, expect, it } from "vitest";

import { resolveApiBase } from "./api-config";

describe("resolveApiBase", () => {
  it("uses an explicitly configured origin in production", () => {
    expect(resolveApiBase(" https://api.example.test ", "production", "android")).toBe(
      "https://api.example.test",
    );
  });

  it("refuses an absent production origin", () => {
    expect(() => resolveApiBase(undefined, "production", "android")).toThrow(
      "EXPO_PUBLIC_API_BASE must be configured for a production mobile build",
    );
  });

  it("refuses a whitespace-only production origin", () => {
    expect(() => resolveApiBase("   ", "production", "ios")).toThrow(
      "EXPO_PUBLIC_API_BASE must be configured for a production mobile build",
    );
  });

  it("keeps the Android emulator fallback in development", () => {
    expect(resolveApiBase(undefined, "development", "android")).toBe("http://10.0.2.2:8090");
  });

  it("keeps the localhost fallback for non-Android development", () => {
    expect(resolveApiBase(undefined, "development", "web")).toBe("http://localhost:8090");
  });
});
