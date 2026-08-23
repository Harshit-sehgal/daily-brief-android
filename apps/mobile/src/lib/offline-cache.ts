import { Platform } from "react-native";
import { Directory, File, Paths } from "expo-file-system";

import type { Board, PortfolioResponse, Project, Task, TodayResponse, WorkSchedule } from "./types";
import {
  cacheFileName,
  createCacheEntry,
  decodeCacheEntry,
  encodeCacheEntry,
} from "./offline-cache-core";
import type { CacheEntry } from "./offline-cache-core";

/** Offline-first payload cache: the last good Today/tasks/board/portfolio payload per workspace,
 *  served when the server is unreachable. Reads only — Apply stays server-authoritative.
 *
 *  Storage is one JSON file per key under the document directory (survives restarts and is
 *  not purged by the OS). On web, where expo-file-system has no implementation, localStorage
 *  provides session-reload persistence with a module-scope fallback for restricted browsers.
 */

const CACHE_DIR = "dailybrief-cache";
const WEB_CACHE_PREFIX = "dailybrief.cache.";

const memory = new Map<string, CacheEntry>();
const writes = new Map<string, Promise<void>>();

function webKey(key: string): string {
  return `${WEB_CACHE_PREFIX}${key}`;
}

function fileFor(key: string): File | null {
  if (Platform.OS === "web") return null;
  return new File(new Directory(Paths.document, CACHE_DIR), cacheFileName(key));
}

async function persist(key: string, entry: CacheEntry): Promise<void> {
  if (Platform.OS === "web") {
    try {
      window.localStorage.setItem(webKey(key), encodeCacheEntry(entry));
    } catch {
      // Browser storage can be disabled or full; memory still helps this session.
    }
    return;
  }
  const file = fileFor(key);
  if (!file) return;
  try {
    const dir = file.parentDirectory;
    if (!dir.exists) dir.create({ intermediates: true, idempotent: true });

    // Write a sibling first, then replace the destination. A process death or
    // full disk must leave the previous complete entry readable.
    const temporary = new File(dir, `${file.name}.${entry.savedAt}.tmp`);
    temporary.write(encodeCacheEntry(entry));
    temporary.moveSync(file, { overwrite: true });
  } catch {
    // A failed write must never fail the request that produced the payload.
  }
}

export async function writeCache(key: string, payload: unknown): Promise<void> {
  const entry = createCacheEntry(payload);
  memory.set(key, entry);

  // Serialize writes per cache key. Without this, a slower write can replace
  // a newer response and make a restart resurrect stale data.
  const previous = writes.get(key) ?? Promise.resolve();
  const next = previous.then(() => persist(key, entry)).finally(() => {
    if (writes.get(key) === next) writes.delete(key);
  });
  writes.set(key, next);
  await next;
}

export async function readCache(key: string): Promise<CacheEntry | null> {
  if (Platform.OS === "web") {
    try {
      const raw = window.localStorage.getItem(webKey(key));
      if (raw) {
        const parsed = decodeCacheEntry(raw);
        if (parsed) {
          memory.set(key, parsed);
          return parsed;
        }
        // Remove a malformed entry so a subsequent successful refresh can
        // recover cleanly instead of parsing the same damaged value forever.
        window.localStorage.removeItem(webKey(key));
      }
    } catch {
      // A corrupt or unavailable browser entry is a miss.
    }
    return memory.get(key) ?? null;
  }
  const file = fileFor(key);
  if (file) {
    try {
      if (file.exists) {
        const parsed = decodeCacheEntry(file.textSync());
        if (parsed) {
          memory.set(key, parsed);
          return parsed;
        }
        // A corrupt native entry is a miss, and deleting it makes recovery
        // deterministic once the next online response is cached.
        file.delete();
      }
    } catch {
      // A corrupt cache file is a miss, not a crash.
    }
  }
  return memory.get(key) ?? null;
}

export function cacheKeys(workspaceId: string) {
  return {
    today: `today-${workspaceId}`,
    planningSettings: `planning-settings-${workspaceId}`,
    tasks: `tasks-${workspaceId}`,
    projects: `projects-${workspaceId}`,
    board: (projectId: string) => `board-${workspaceId}-${projectId}`,
    portfolio: `portfolio-${workspaceId}`,
  };
}

export type Cached<T> = { data: T; stale: boolean; savedAt: number };

export type CachedToday = Cached<TodayResponse>;
export type CachedPlanningSettings = Cached<WorkSchedule>;
export type CachedTasks = Cached<Task[]>;
export type CachedBoard = Cached<Board>;
export type CachedProjects = Cached<Project[]>;
export type CachedPortfolio = Cached<PortfolioResponse>;
