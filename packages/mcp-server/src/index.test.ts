import { describe, expect, test } from "bun:test";

import { createMcpServer, startStdioServer } from "./index.js";
import type { McpServerDependencies, StdioServerRuntimeDependencies } from "./index.js";

describe("package root exports", () => {
  test("exports the supported runtime entrypoints", () => {
    expect(typeof createMcpServer).toBe("function");
    expect(typeof startStdioServer).toBe("function");
  });

  test("exports the supported dependency types", () => {
    const mcpServerDependencies: McpServerDependencies = {};
    const stdioServerRuntimeDependencies: StdioServerRuntimeDependencies = {};

    expect(mcpServerDependencies).toEqual({});
    expect(stdioServerRuntimeDependencies).toEqual({});
  });

  test("does not export cli-only process runners", async () => {
    const moduleExports = await import("./index.js");

    expect(moduleExports).not.toHaveProperty("runStdioServerCli");
  });
});
