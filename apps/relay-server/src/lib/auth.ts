import { createHash, timingSafeEqual } from "node:crypto";

/**
 * Parses a comma-separated token allowlist and rejects blank or empty entries.
 */
export function parseTokenAllowlist(envValue: string): string[] {
  const tokens = envValue
    .split(",")
    .map((token) => token.trim());

  if (tokens.some((token) => token.length === 0)) {
    throw new Error("allowlist contains blank token entries");
  }

  const dedupedTokens = [...new Set(tokens)];
  if (dedupedTokens.length === 0) {
    throw new Error("allowlist must contain at least one token");
  }

  return dedupedTokens;
}

/**
 * Returns the bearer token when the header matches the exact Bearer format.
 */
export function extractBearerToken(authHeader: string): string | null {
  const match = /^Bearer ([^\s]+)$/.exec(authHeader);
  return match?.[1] ?? null;
}

/**
 * Creates a fixed-length SHA-256 hash buffer for constant-time comparison.
 * This ensures all comparisons take the same amount of time regardless of
 * token content or position in the allowlist.
 */
function hashToken(token: string): Buffer {
  return createHash("sha256").update(token).digest();
}

/**
 * Performs constant-time comparison of two hashed tokens.
 * Always compares full 32-byte hashes without early exit.
 */
function hashesMatch(leftHash: Buffer, rightHash: Buffer): boolean {
  // Both hashes are guaranteed to be 32 bytes (SHA-256)
  // timingSafeEqual performs constant-time comparison
  return timingSafeEqual(leftHash, rightHash);
}

/**
 * Checks whether a candidate token matches any token in the allowlist.
 * Uses constant-time comparison with pre-hashed tokens to prevent
 * timing attacks that could reveal valid token information.
 */
export function validateTokenAgainstAllowlist(token: string, allowlist: string[]): boolean {
  const candidateHash = hashToken(token);
  
  // Compare against all allowlist entries using constant-time comparison
  // Fold all comparison results together to avoid early exit
  let matchFound = false;
  for (const allowedToken of allowlist) {
    const allowedHash = hashToken(allowedToken);
    if (hashesMatch(candidateHash, allowedHash)) {
      matchFound = true;
    }
  }
  
  return matchFound;
}

/**
 * Produces a stable redacted token fingerprint suitable for logs.
 */
export function createTokenFingerprint(token: string): string {
  const digest = createHash("sha256").update(token).digest("hex");
  return `sha256:${digest.slice(0, 12)}`;
}
