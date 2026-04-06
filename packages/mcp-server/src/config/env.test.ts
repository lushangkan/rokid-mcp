import { describe, expect, test } from "bun:test";

import { readMcpEnv } from "./env.js";

describe("readMcpEnv", () => {
  test("reads defaults when optional values are missing", () => {
    const env = readMcpEnv({
      RELAY_BASE_URL: "https://relay.example.com",
      ROKID_DEFAULT_DEVICE_ID: "rokid_glasses_01",
    });

    expect(env.relayBaseUrl).toBe("https://relay.example.com");
    expect(env.requestTimeoutMs).toBe(5000);
    expect(env.defaultDeviceId).toBe("rokid_glasses_01");
    expect(env.commandPollIntervalMs).toBe(1000);
    expect(env.commandTimeoutMs).toBe(90000);
  });

  test("throws when RELAY_BASE_URL is missing", () => {
    expect(() => readMcpEnv({})).toThrow("Missing required environment variable: RELAY_BASE_URL");
  });

  test("throws when ROKID_DEFAULT_DEVICE_ID is missing", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "https://relay.example.com",
      })
    ).toThrow("Missing required environment variable: ROKID_DEFAULT_DEVICE_ID");
  });

  test("throws when ROKID_DEFAULT_DEVICE_ID is invalid", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "https://relay.example.com",
        ROKID_DEFAULT_DEVICE_ID: "bad id with spaces",
      })
    ).toThrow("Invalid device id environment variable: ROKID_DEFAULT_DEVICE_ID");
  });

  test("throws on invalid timeout value", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "https://relay.example.com",
        ROKID_DEFAULT_DEVICE_ID: "rokid_glasses_01",
        RELAY_REQUEST_TIMEOUT_MS: "oops",
      })
    ).toThrow("Invalid numeric environment variable: RELAY_REQUEST_TIMEOUT_MS");
  });

  test("throws when timeout is not a positive integer", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "https://relay.example.com",
        ROKID_DEFAULT_DEVICE_ID: "rokid_glasses_01",
        RELAY_REQUEST_TIMEOUT_MS: "0",
      })
    ).toThrow("Environment variable RELAY_REQUEST_TIMEOUT_MS must be a positive integer");
  });

  test("throws when timeout is a non-integer value", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "https://relay.example.com",
        ROKID_DEFAULT_DEVICE_ID: "rokid_glasses_01",
        RELAY_REQUEST_TIMEOUT_MS: "1.5",
      })
    ).toThrow("Environment variable RELAY_REQUEST_TIMEOUT_MS must be a positive integer");
  });

  test("throws when RELAY_BASE_URL is not a valid URL", () => {
    expect(() =>
      readMcpEnv({
        RELAY_BASE_URL: "not-a-url",
        ROKID_DEFAULT_DEVICE_ID: "rokid_glasses_01",
      })
    ).toThrow("Invalid URL environment variable: RELAY_BASE_URL");
  });

  test("prefers MCP_REQUEST_TIMEOUT_MS over legacy relay timeout env", () => {
    const env = readMcpEnv({
      RELAY_BASE_URL: "https://relay.example.com",
      ROKID_DEFAULT_DEVICE_ID: "rokid_glasses_01",
      MCP_REQUEST_TIMEOUT_MS: "7000",
      RELAY_REQUEST_TIMEOUT_MS: "2000",
    });

    expect(env.requestTimeoutMs).toBe(7000);
  });
});
