import { describe, expect, test } from "bun:test";

import { Glob } from "bun";

describe("relay-server source import paths", () => {
  test("detects local relative .js paths across import styles", () => {
    const samples = [
      'import "./setup.js";',
      'const module = await import("../utils/file.js");',
      'export * from "./module.js";',
      'import { value } from "./value.js";',
    ];

    expect(samples.filter(hasLocalRelativeJsImport)).toEqual(samples);
  });

  test("source files do not use local relative .js import extensions", async () => {
    const glob = new Glob("**/*.ts");
    const offenderLines: string[] = [];

    for await (const relativePath of glob.scan({ cwd: new URL("./", import.meta.url).pathname })) {
      if (relativePath === "import-paths.test.ts") {
        continue;
      }

      const absolutePath = new URL(relativePath, import.meta.url);
      const content = await Bun.file(absolutePath).text();
      const lines = content.split("\n");

      lines.forEach((line, index) => {
        const trimmed = line.trim();
        if (!hasLocalRelativeJsImport(trimmed)) {
          return;
        }

        offenderLines.push(`${relativePath}:${index + 1}:${trimmed}`);
      });
    }

    expect(offenderLines).toEqual([]);
  });
});

const LOCAL_RELATIVE_JS_IMPORT_PATTERN = /(?:from\s+|import\s*\()(["'])(\.\.?\/[^"']*\.js)\1|^import\s+(["'])(\.\.?\/[^"']*\.js)\3/;

function hasLocalRelativeJsImport(line: string): boolean {
  return LOCAL_RELATIVE_JS_IMPORT_PATTERN.test(line.trim());
}
