import { describe, expect, it } from "vitest";

import {
  CACHE_VERSION,
  cacheFileName,
  createCacheEntry,
  decodeCacheEntry,
  encodeCacheEntry,
} from "./offline-cache-core";

describe("offline cache persistence rules", () => {
  it("round-trips a payload with a stable timestamp and version", () => {
    const entry = createCacheEntry({ workspaceId: "ws-a", tasks: ["t1"] }, 1234);

    expect(decodeCacheEntry(encodeCacheEntry(entry))).toEqual({
      version: CACHE_VERSION,
      savedAt: 1234,
      payload: { workspaceId: "ws-a", tasks: ["t1"] },
    });
  });

  it("accepts a legacy v1 entry and rejects corrupt or unsafe shapes", () => {
    expect(decodeCacheEntry('{"savedAt":12,"payload":{"ok":true}}')).toEqual({
      version: CACHE_VERSION,
      savedAt: 12,
      payload: { ok: true },
    });
    expect(decodeCacheEntry("not-json")).toBeNull();
    expect(decodeCacheEntry('{"savedAt":"12","payload":{}}')).toBeNull();
    expect(decodeCacheEntry('{"version":2,"savedAt":12,"payload":{}}')).toBeNull();
    expect(decodeCacheEntry('{"savedAt":-1,"payload":{}}')).toBeNull();
    expect(decodeCacheEntry('{"savedAt":12}')).toBeNull();
  });

  it("keeps workspace/project keys isolated and path-safe", () => {
    const a = cacheFileName("board/workspace-a/project-a");
    const b = cacheFileName("board/workspace-b/project-a");
    expect(a).not.toBe(b);
    expect(a).not.toContain("/");
    expect(b).not.toContain("/");
  });

  it("does not treat a failed decode as usable persisted data", () => {
    const first = createCacheEntry({ revision: 1 }, 100);
    const damaged = encodeCacheEntry(first).slice(0, -2);
    expect(decodeCacheEntry(damaged)).toBeNull();
  });
});
