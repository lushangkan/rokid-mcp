import { describe, expect, test } from "bun:test";

import {
  createTokenFingerprint,
  extractBearerToken,
  parseTokenAllowlist,
  validateTokenAgainstAllowlist,
} from "./auth.ts";

describe("parseTokenAllowlist", () => {
  test("trims entries and dedupes identical tokens", () => {
    expect(parseTokenAllowlist("  token-a, token-b ,token-a ")).toEqual(["token-a", "token-b"]);
  });

  test("rejects blank entries", () => {
    expect(() => parseTokenAllowlist("token-a,,token-b")).toThrow("allowlist contains blank token entries");
    expect(() => parseTokenAllowlist("   ")).toThrow("allowlist contains blank token entries");
  });
});

describe("extractBearerToken", () => {
  test("accepts only exact bearer authorization format", () => {
    expect(extractBearerToken("Bearer token-123")).toBe("token-123");
  });

  test("rejects missing or malformed bearer authorization headers", () => {
    expect(extractBearerToken("")).toBeNull();
    expect(extractBearerToken("token-123")).toBeNull();
    expect(extractBearerToken("bearer token-123")).toBeNull();
    expect(extractBearerToken("Bearer ")).toBeNull();
    expect(extractBearerToken("Bearer token 123")).toBeNull();
  });
});

describe("validateTokenAgainstAllowlist", () => {
  test("returns true only for allowlisted tokens", () => {
    expect(validateTokenAgainstAllowlist("token-a", ["token-a", "token-b"])).toBe(true);
    expect(validateTokenAgainstAllowlist("token-c", ["token-a", "token-b"])).toBe(false);
  });
});

describe("createTokenFingerprint", () => {
  test("returns a stable redacted fingerprint", () => {
    const fingerprint = createTokenFingerprint("super-secret-token");

    expect(fingerprint).toMatch(/^sha256:[0-9a-f]{12}$/);
    expect(fingerprint).toBe(createTokenFingerprint("super-secret-token"));
    expect(fingerprint).not.toContain("super-secret-token");
  });
});
