import { describe, expect, test } from "bun:test";
import { createApp } from "./app.js";

describe("relay app", () => {
  test("health route responds", async () => {
    const app = createApp();
    const response = await app.handle(new Request("http://localhost/health"));
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.ok).toBe(true);
    expect(json.service).toBe("relay-server");
  });
});
