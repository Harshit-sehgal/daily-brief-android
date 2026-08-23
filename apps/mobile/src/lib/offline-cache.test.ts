import { beforeEach, describe, expect, it, vi } from "vitest";

const values = new Map<string, string>();
const localStorage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => values.set(key, value),
  removeItem: (key: string) => values.delete(key),
  clear: () => values.clear(),
};

vi.stubGlobal("window", { localStorage });

vi.mock("react-native", () => ({ Platform: { OS: "web" } }));
vi.mock("expo-file-system", () => ({
  Directory: class Directory {},
  File: class File {},
  Paths: { document: {} },
}));

async function loadCache() {
  vi.resetModules();
  return import("./offline-cache");
}

describe("offline cache adapter", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("survives a module reload through web storage", async () => {
    const first = await loadCache();
    await first.writeCache("today-workspace-a", { revision: 1 });

    const second = await loadCache();
    await expect(second.readCache("today-workspace-a")).resolves.toMatchObject({
      payload: { revision: 1 },
    });
  });

  it("removes malformed persisted data and treats it as a miss", async () => {
    localStorage.setItem("dailybrief.cache.today-workspace-a", "{broken");

    const cache = await loadCache();
    await expect(cache.readCache("today-workspace-a")).resolves.toBeNull();
    expect(localStorage.getItem("dailybrief.cache.today-workspace-a")).toBeNull();
  });

  it("keeps the successful in-memory response when storage is unavailable", async () => {
    const cache = await loadCache();
    const setItem = localStorage.setItem;
    localStorage.setItem = () => {
      throw new Error("storage full");
    };

    await expect(cache.writeCache("today-workspace-a", { revision: 2 })).resolves.toBeUndefined();
    await expect(cache.readCache("today-workspace-a")).resolves.toMatchObject({
      payload: { revision: 2 },
    });

    localStorage.setItem = setItem;
    const reloaded = await loadCache();
    await expect(reloaded.readCache("today-workspace-a")).resolves.toBeNull();
  });
});
