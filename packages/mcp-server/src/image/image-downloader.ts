import type { ImageContentType } from "@rokid-mcp/protocol";

import { RelayRequestError } from "../lib/errors.js";
import type { DownloadedRelayImage, RelayCommandClient } from "../relay/relay-command-client.js";

const PASSTHROUGH_DOWNLOAD_ERROR_CODES = new Set([
  "IMAGE_NOT_FOUND",
  "IMAGE_NOT_READY",
  "IMAGE_EXPIRED",
  "AUTH_FORBIDDEN_IMAGE_ACCESS",
]);

export type ImageDownloadRequest = {
  imageId: string;
  expectedSize: number;
  expectedMimeType: ImageContentType;
};

export type ImageDownloader = {
  download(request: ImageDownloadRequest): Promise<DownloadedRelayImage>;
};

export function createImageDownloader(deps: {
  relayCommandClient: Pick<RelayCommandClient, "downloadImage">;
}): ImageDownloader {
  return {
    async download(request) {
      try {
        const downloadedImage = await deps.relayCommandClient.downloadImage(request.imageId);
        validateDownloadedImage(downloadedImage, request);
        return downloadedImage;
      } catch (error) {
        throw normalizeDownloadError(error, request);
      }
    },
  };
}

function validateDownloadedImage(downloadedImage: DownloadedRelayImage, request: ImageDownloadRequest): void {
  if (downloadedImage.bytes.byteLength === 0) {
    throw createDownloadFailedError("Relay image download returned an empty image body.", request, false);
  }

  if (downloadedImage.mimeType !== request.expectedMimeType) {
    throw createDownloadFailedError("Relay image download returned an unexpected MIME type.", request, false, {
      receivedMimeType: downloadedImage.mimeType,
    });
  }

  if (downloadedImage.size !== downloadedImage.bytes.byteLength) {
    throw createDownloadFailedError("Relay image download metadata size did not match the downloaded bytes.", request, false, {
      receivedSize: downloadedImage.size,
      byteLength: downloadedImage.bytes.byteLength,
    });
  }

  if (downloadedImage.bytes.byteLength !== request.expectedSize) {
    throw createDownloadFailedError("Relay image download size did not match the completed command metadata.", request, false, {
      receivedSize: downloadedImage.bytes.byteLength,
    });
  }
}

function normalizeDownloadError(error: unknown, request: ImageDownloadRequest): RelayRequestError {
  if (error instanceof RelayRequestError) {
    if (PASSTHROUGH_DOWNLOAD_ERROR_CODES.has(error.code) || error.code === "MCP_IMAGE_DOWNLOAD_FAILED") {
      return error;
    }

    return createDownloadFailedError(`Failed to download captured image ${request.imageId}.`, request, error.retryable, {
      causeCode: error.code,
      cause: error.message,
      ...(error.details ? { causeDetails: error.details } : {}),
    });
  }

  return createDownloadFailedError(`Failed to download captured image ${request.imageId}.`, request, true, {
    cause: error instanceof Error ? error.message : String(error),
  });
}

function createDownloadFailedError(
  message: string,
  request: ImageDownloadRequest,
  retryable: boolean,
  details: Record<string, unknown> = {},
): RelayRequestError {
  return new RelayRequestError("MCP_IMAGE_DOWNLOAD_FAILED", message, retryable, {
    imageId: request.imageId,
    expectedSize: request.expectedSize,
    expectedMimeType: request.expectedMimeType,
    ...details,
  });
}
