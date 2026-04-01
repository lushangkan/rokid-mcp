import { describe, expect, test } from "bun:test";

import { readMcpEnv } from "./env.js";

describe("readMcpEnv", () => {
  test("reads defaults when optional values are missing", () => {
    const env = readMcpEnv({
      RELAY_BASE_URL: "https://relay.example.com",
    });

    expect(env.relayBaseUrl).toBe("https://relay.example.com");
    expect(env.requestTimeoutMs).toBe(5000);
  });

  test("throws when RELAY_BASE_URL is missing", () => {
    expect(() => readMcpEnv({})).toThrow("Missing required environment variable: RELAY_BASE_URL");
  });

  test("throws on invalid timeout value", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "https://relay.example.com",
        RELAY_REQUEST_TIMEOUT_MS: "oops",
      })
    ).toThrow("Invalid numeric environment variable: RELAY_REQUEST_TIMEOUT_MS");
  });

  test("throws when timeout is not a positive integer", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "https://relay.example.com",
        RELAY_REQUEST_TIMEOUT_MS: "0",
      })
    ).toThrow("Environment variable RELAY_REQUEST_TIMEOUT_MS must be a positive integer");
  });

  test("throws when timeout is a non-integer value", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "https://relay.example.com",
        RELAY_REQUEST_TIMEOUT_MS: "1.5",
      })
    ).toThrow("Environment variable RELAY_REQUEST_TIMEOUT_MS must be a positive integer");
  });

  test("throws when RELAY_BASE_URL is not a valid URL", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "not-a-url",
      })
    ).toThrow("Invalid URL environment variable: RELAY_BASE_URL");
  });
});
