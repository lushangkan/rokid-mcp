import { createHash, timingSafeEqual } from "node:crypto";

export class UploadTokenManager {
  createTokenHash(uploadToken: string): string {
    return createHash("sha256").update(uploadToken).digest("hex");
  }

  matches(uploadToken: string, expectedTokenHash: string): boolean {
    const actual = Buffer.from(this.createTokenHash(uploadToken), "utf8");
    const expected = Buffer.from(expectedTokenHash, "utf8");

    if (actual.length !== expected.length) {
      return false;
    }

    return timingSafeEqual(actual, expected);
  }
}
