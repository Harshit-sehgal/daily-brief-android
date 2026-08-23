import { beforeEach, describe, expect, it, vi } from "vitest";

const files = new Map<string, string>();
const directories = new Set<string>();
let failWrites = false;

function uriOf(parent: string | { uri: string }, name?: string): string {
  const base = typeof parent === "string" ? parent : parent.uri;
  return name ? `${base.replace(/\/$/, "")}/${name}` : base;
}

class Directory {
  readonly uri: string;

  constructor(parent: string | Directory, name?: string) {
    this.uri = uriOf(parent, name);
  }

  get exists(): boolean {
    return directories.has(this.uri);
  }

  create(): void {
    directories.add(this.uri);
  }
}

class File {
  readonly uri: string;

  constructor(parent: Directory | string, name?: string) {
    this.uri = uriOf(parent, name);
  }

  get name(): string {
    return this.uri.slice(this.uri.lastIndexOf("/") + 1);
  }

  get parentDirectory(): Directory {
    return new Directory(this.uri.slice(0, this.uri.lastIndexOf("/")));
  }

  get exists(): boolean {
    return files.has(this.uri);
  }

  write(value: string): void {
    const parent = this.uri.slice(0, this.uri.lastIndexOf("/"));
    if (!directories.has(parent)) throw new Error("parent directory missing");
    if (failWrites && this.name.endsWith(".tmp")) throw new Error("disk full");
    files.set(this.uri, value);
  }

  textSync(): string {
    const value = files.get(this.uri);
    if (value === undefined) throw new Error("missing file");
    return value;
  }

  moveSync(destination: File, options?: { overwrite?: boolean }): void {
    if (!files.has(this.uri)) throw new Error("missing source");
    if (files.has(destination.uri) && !options?.overwrite) throw new Error("destination exists");
    files.set(destination.uri, files.get(this.uri)!);
    files.delete(this.uri);
  }

  delete(): void {
    files.delete(this.uri);
  }
}

vi.mock("react-native", () => ({ Platform: { OS: "android" } }));
vi.mock("expo-file-system", () => ({
  Directory,
  File,
  Paths: { document: "document://user" },
}));

async function loadCache() {
  vi.resetModules();
  return import("./offline-cache");
}

describe("offline cache native adapter", () => {
  beforeEach(() => {
    files.clear();
    directories.clear();
    failWrites = false;
  });

  it("survives a module reload through document storage and isolates workspaces", async () => {
    const first = await loadCache();
    await first.writeCache("today-workspace/a", { revision: 1 });
    await first.writeCache("today-workspace-b", { revision: 2 });

    const second = await loadCache();
    await expect(second.readCache("today-workspace/a")).resolves.toMatchObject({
      payload: { revision: 1 },
    });
    await expect(second.readCache("today-workspace-b")).resolves.toMatchObject({
      payload: { revision: 2 },
    });
    expect(files.size).toBe(2);
  });

  it("rejects and removes a corrupt native entry", async () => {
    const path = new File(
      new Directory("document://user", "dailybrief-cache"),
      "today-workspace-a.json"
    );
    path.parentDirectory.create();
    path.write("{broken");

    const cache = await loadCache();
    await expect(cache.readCache("today-workspace-a")).resolves.toBeNull();
    expect(path.exists).toBe(false);
  });

  it("keeps the last complete entry after a failed atomic replacement", async () => {
    const first = await loadCache();
    await first.writeCache("today-workspace-a", { revision: 1 });

    failWrites = true;
    await first.writeCache("today-workspace-a", { revision: 2 });

    const reloaded = await loadCache();
    await expect(reloaded.readCache("today-workspace-a")).resolves.toMatchObject({
      payload: { revision: 1 },
    });
  });
});
