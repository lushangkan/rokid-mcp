import { describe, expect, test } from "bun:test";
import { readFile } from "node:fs/promises";

type PackageManifest = {
  private?: boolean;
  type?: string;
  bin?: Record<string, string>;
  exports?: Record<string, unknown>;
  scripts?: Record<string, string>;
};

const packageManifest = JSON.parse(
  await readFile(new URL("../package.json", import.meta.url), "utf8"),
) as PackageManifest;

describe("package shape", () => {
  test("keeps the internal cli-first contract explicit", () => {
    expect(packageManifest.private).toBe(true);
    expect(packageManifest.type).toBe("module");
    expect(packageManifest.bin).toEqual({
      "rokid-mcp-server": "./dist/cli.js",
    });
    expect(packageManifest.exports).toEqual({
      ".": {
        bun: "./src/index.ts",
        types: "./dist/index.d.ts",
        import: "./dist/index.js",
      },
    });
    expect(packageManifest.scripts).toMatchObject({
      build: "tsc -b",
      typecheck: "tsc -b --pretty false",
      start: "node ./dist/cli.js",
    });
    expect(packageManifest.scripts).not.toHaveProperty("dev:stdio");
  });
});
