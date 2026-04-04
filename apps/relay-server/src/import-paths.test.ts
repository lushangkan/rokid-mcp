import { describe, expect, test } from "bun:test";

import { Glob } from "bun";

describe("relay-server source import paths", () => {
  test("source files do not use local relative .js import extensions", async () => {
    const glob = new Glob("**/*.ts");
    const offenderLines: string[] = [];

    for await (const relativePath of glob.scan({ cwd: new URL("./", import.meta.url).pathname })) {
      const absolutePath = new URL(relativePath, import.meta.url);
      const content = await Bun.file(absolutePath).text();
      const lines = content.split("\n");

      lines.forEach((line, index) => {
        const trimmed = line.trim();
        if (!trimmed.startsWith("import") && !trimmed.startsWith("export")) {
          return;
        }

        if (!trimmed.includes('from "./') && !trimmed.includes('from "../') && !trimmed.includes('from \"./') && !trimmed.includes('from \"../')) {
          return;
        }

        if (trimmed.includes(".js\"") || trimmed.includes('.js"') || trimmed.includes(".js'") || trimmed.includes(".js\";")) {
          offenderLines.push(`${relativePath}:${index + 1}:${trimmed}`);
        }
      });
    }

    expect(offenderLines).toEqual([]);
  });
});
