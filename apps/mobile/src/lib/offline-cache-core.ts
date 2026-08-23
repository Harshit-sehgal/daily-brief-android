/** Platform-neutral rules for the persisted offline cache.
 *
 * Keeping encoding and validation out of the Expo adapter makes the failure
 * cases testable without pretending a Node test is a native-device test.
 */

export const CACHE_VERSION = 1;

export interface CacheEntry {
  version: number;
  savedAt: number;
  payload: unknown;
}

export function createCacheEntry(payload: unknown, savedAt = Date.now()): CacheEntry {
  return { version: CACHE_VERSION, savedAt, payload };
}

export function encodeCacheEntry(entry: CacheEntry): string {
  return JSON.stringify(entry);
}

/** Decode only the shape this client wrote. Old entries without a version are
 * accepted as v1 so an app update does not discard a usable cache. */
export function decodeCacheEntry(raw: string): CacheEntry | null {
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object") return null;

    const candidate = value as {
      version?: unknown;
      savedAt?: unknown;
      payload?: unknown;
    };
    const version = candidate.version === undefined ? CACHE_VERSION : candidate.version;
    if (version !== CACHE_VERSION || typeof candidate.savedAt !== "number") return null;
    if (!Number.isFinite(candidate.savedAt) || candidate.savedAt < 0) return null;
    if (!("payload" in candidate)) return null;

    return { version: CACHE_VERSION, savedAt: candidate.savedAt, payload: candidate.payload };
  } catch {
    return null;
  }
}

/** Cache keys are data, not paths. Encode every key component before using it
 * as a filename so a provider/workspace id cannot escape the cache directory. */
export function cacheFileName(key: string): string {
  return `${encodeURIComponent(key)}.json`;
}
