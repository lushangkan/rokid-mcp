import { createHash } from "node:crypto";
import type { ImageContentType } from "@rokid-mcp/protocol";

import { RelayRequestError } from "../lib/errors.js";
import type { DownloadedRelayImage } from "../relay/relay-command-client.js";

export type ExpectedCapturePhotoImage = {
  imageId: string;
  transferId: string;
  mimeType: ImageContentType;
  size: number;
  width: number;
  height: number;
  sha256?: string;
};

export type VerifiedDownloadedImage = ExpectedCapturePhotoImage & {
  bytes: Uint8Array;
};

export type DownloadedImageVerifier = {
  verify(input: {
    downloadedImage: DownloadedRelayImage;
    expectedImage: ExpectedCapturePhotoImage;
  }): VerifiedDownloadedImage;
};

export function createDownloadedImageVerifier(): DownloadedImageVerifier {
  return {
    verify({ downloadedImage, expectedImage }) {
      assertMetadataMatches(downloadedImage, expectedImage);

      const sha256 = verifyChecksum(downloadedImage, expectedImage);

      return {
        ...expectedImage,
        ...(sha256 ? { sha256 } : {}),
        bytes: downloadedImage.bytes,
      };
    },
  };
}

function assertMetadataMatches(downloadedImage: DownloadedRelayImage, expectedImage: ExpectedCapturePhotoImage): void {
  if (downloadedImage.imageId !== expectedImage.imageId) {
    throw createParseFailedError("Downloaded image ID did not match the completed command metadata.", {
      expectedImageId: expectedImage.imageId,
      receivedImageId: downloadedImage.imageId,
    });
  }

  if (downloadedImage.transferId !== expectedImage.transferId) {
    throw createParseFailedError("Downloaded transfer ID did not match the completed command metadata.", {
      expectedTransferId: expectedImage.transferId,
      receivedTransferId: downloadedImage.transferId,
    });
  }

  if (downloadedImage.mimeType !== expectedImage.mimeType) {
    throw createParseFailedError("Downloaded image MIME type did not match the completed command metadata.", {
      expectedMimeType: expectedImage.mimeType,
      receivedMimeType: downloadedImage.mimeType,
    });
  }

  if (downloadedImage.size !== expectedImage.size) {
    throw createParseFailedError("Downloaded image size did not match the completed command metadata.", {
      expectedSize: expectedImage.size,
      receivedSize: downloadedImage.size,
    });
  }

  if (expectedImage.sha256 && downloadedImage.sha256 && expectedImage.sha256 !== downloadedImage.sha256) {
    throw createParseFailedError("Downloaded image checksum metadata did not match the completed command metadata.", {
      expectedSha256: expectedImage.sha256,
      receivedSha256: downloadedImage.sha256,
    });
  }
}

function verifyChecksum(downloadedImage: DownloadedRelayImage, expectedImage: ExpectedCapturePhotoImage): string | undefined {
  const expectedSha256 = expectedImage.sha256 ?? downloadedImage.sha256;
  if (!expectedSha256) {
    return undefined;
  }

  const digest = createHash("sha256").update(downloadedImage.bytes).digest("hex");
  if (digest !== expectedSha256) {
    throw createParseFailedError("Downloaded image checksum did not match the expected SHA-256.", {
      imageId: expectedImage.imageId,
      expectedSha256,
      receivedSha256: digest,
    });
  }

  return expectedSha256;
}

function createParseFailedError(message: string, details: Record<string, unknown>): RelayRequestError {
  return new RelayRequestError("MCP_IMAGE_PARSE_FAILED", message, false, details);
}
