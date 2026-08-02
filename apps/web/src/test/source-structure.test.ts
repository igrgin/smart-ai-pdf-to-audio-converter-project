import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = path.resolve(process.cwd(), "src");
const owningAreas = new Set([
  "app",
  "session",
  "public-studio",
  "library",
  "audiobook-creation",
  "narration-review",
  "playback",
  "offline-copy",
  "trust-operations",
  "ui",
  "test"
]);
const rootFiles = new Set(["main.tsx", "vite-env.d.ts"]);
const sourceExtension = /\.(?:ts|tsx)$/;
const importPattern = /(?:import|export)\s+(?:[^"']*?\s+from\s+)?["']([^"']+)["']/g;

describe("React ownership structure", () => {
  it("places production source in an owning feature or application area", () => {
    const misplaced = readdirSync(sourceRoot)
      .filter((entry) => {
        const entryPath = path.join(sourceRoot, entry);
        return statSync(entryPath).isDirectory() ? !owningAreas.has(entry) : !rootFiles.has(entry);
      });

    expect(misplaced).toEqual([]);
  });

  it("uses feature entry points and keeps feature dependencies acyclic", () => {
    const deepImports: string[] = [];
    const dependencies = new Map<string, Set<string>>();
    for (const source of productionSources(sourceRoot)) {
      const owner = ownerOf(source);
      if (!owner || owner === "test") continue;
      dependencies.set(owner, dependencies.get(owner) ?? new Set());
      const content = readFileSync(source, "utf8");
      for (const match of content.matchAll(importPattern)) {
        const specifier = match[1];
        if (!specifier.startsWith(".")) continue;
        const target = path.resolve(path.dirname(source), specifier);
        const targetOwner = ownerOf(target);
        if (!targetOwner || targetOwner === owner || targetOwner === "test") continue;
        dependencies.get(owner)?.add(targetOwner);
        const targetRelative = path.relative(sourceRoot, target).split(path.sep);
        if (targetRelative.length !== 1) {
          deepImports.push(`${path.relative(sourceRoot, source)} -> ${specifier}`);
        }
      }
    }

    expect(deepImports).toEqual([]);
    expect(findCycles(dependencies)).toEqual([]);
  });
});

function productionSources(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const entryPath = path.join(directory, entry);
    if (statSync(entryPath).isDirectory()) return productionSources(entryPath);
    if (!sourceExtension.test(entry) || entry.endsWith(".test.ts") || entry.endsWith(".test.tsx")) return [];
    return [entryPath];
  });
}

function ownerOf(target: string): string | undefined {
  const relative = path.relative(sourceRoot, target);
  if (relative.startsWith("..")) return undefined;
  const first = relative.split(path.sep)[0];
  return sourceExtension.test(first) ? "app" : first;
}

function findCycles(dependencies: Map<string, Set<string>>): string[] {
  const cycles: string[] = [];
  const complete = new Set<string>();
  const active = new Set<string>();
  const route: string[] = [];
  const visit = (owner: string) => {
    if (active.has(owner)) {
      cycles.push([...route, owner].join(" -> "));
      return;
    }
    if (complete.has(owner) || owner === "app") return;
    active.add(owner);
    route.push(owner);
    for (const dependency of dependencies.get(owner) ?? []) visit(dependency);
    route.pop();
    active.delete(owner);
    complete.add(owner);
  };
  for (const owner of dependencies.keys()) visit(owner);
  return [...new Set(cycles)].sort();
}
