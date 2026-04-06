import { Value } from "@sinclair/typebox/value";
import {
  DEFAULT_IMAGE_CONTENT_TYPE,
  ErrorResponseSchema,
  ImageDownloadResponseSchema,
  type GetCommandStatusResponse,
  type ImageDownloadResponse,
  type SubmitCommandRequest,
  type SubmitCommandResponse,
} from "@rokid-mcp/protocol";

import { RelayRequestError } from "../lib/errors.js";
import type { RelayClientConfig } from "./relay-client.js";
import {
  validateRelayGetCommandStatusResponse,
  validateRelaySubmitCommandResponse,
  type RelayValidationResult,
} from "./relay-response-validator.js";

export type RelayCommandClientConfig = RelayClientConfig;

export type DownloadedRelayImage = ImageDownloadResponse["image"] & {
  bytes: Uint8Array;
};

export type RelayCommandClient = {
  submitCommand(request: SubmitCommandRequest): Promise<SubmitCommandResponse>;
  getCommandStatus(requestId: string): Promise<GetCommandStatusResponse>;
  downloadImage(imageId: string): Promise<DownloadedRelayImage>;
};

export function createRelayCommandClient(config: RelayCommandClientConfig): RelayCommandClient {
  return {
    async submitCommand(request) {
      return requestJson({
        config,
        path: "/api/v1/commands",
        method: "POST",
        body: request,
        requestDescription: "relay command submission",
        validate: validateRelaySubmitCommandResponse,
      });
    },

    async getCommandStatus(requestId) {
      return requestJson({
        config,
        path: `/api/v1/commands/${encodeURIComponent(requestId)}`,
        method: "GET",
        requestDescription: "relay command status",
        validate: validateRelayGetCommandStatusResponse,
      });
    },

    async downloadImage(imageId) {
      const response = await sendRequest({
        config,
        path: `/api/v1/images/${encodeURIComponent(imageId)}`,
        method: "GET",
        headers: {
          accept: "image/jpeg",
        },
        requestDescription: "relay image download",
      });

      const contentType = normalizeMimeType(response.headers.get("content-type"));
      if (contentType.includes("application/json")) {
        const payload = await parseJsonResponse(response, "relay image download error response");

        if (Value.Check(ErrorResponseSchema, payload)) {
          throw new RelayRequestError(
            payload.error.code,
            payload.error.message,
            payload.error.retryable,
            payload.error.details,
          );
        }

        throw new RelayRequestError(
          "MCP_RELAY_RESPONSE_INVALID",
          "Relay image download returned JSON instead of image bytes",
          false,
        );
      }

      const bytes = new Uint8Array(await response.arrayBuffer());
      const metadata = buildDownloadedImageMetadata(response, bytes);
      return {
        ...metadata,
        bytes,
      };
    },
  };
}

async function requestJson<T>(options: {
  config: RelayCommandClientConfig;
  path: string;
  method: "GET" | "POST";
  body?: unknown;
  headers?: HeadersInit;
  requestDescription: string;
  validate(value: unknown): RelayValidationResult<T>;
}): Promise<T> {
  const response = await sendRequest(options);
  const payload = await parseJsonResponse(response, options.requestDescription);
  const validation = options.validate(payload);

  if (!validation.ok) {
    throw new RelayRequestError(
      validation.error.code,
      validation.error.message,
      validation.error.retryable,
      validation.error.details,
    );
  }

  return validation.value;
}

async function sendRequest(options: {
  config: RelayCommandClientConfig;
  path: string;
  method: "GET" | "POST";
  body?: unknown;
  headers?: HeadersInit;
  requestDescription: string;
}): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, options.config.requestTimeoutMs);

  try {
    return await fetch(`${options.config.relayBaseUrl}${options.path}`, {
      method: options.method,
      headers: options.body
        ? {
            "content-type": "application/json",
            ...options.headers,
          }
        : options.headers,
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal,
    });
  } catch (error) {
    throw new RelayRequestError(
      "MCP_RELAY_REQUEST_FAILED",
      `Failed to request ${options.requestDescription}`,
      true,
      {
        cause: error instanceof Error ? error.message : String(error),
      },
    );
  } finally {
    clearTimeout(timer);
  }
}

async function parseJsonResponse(response: Response, requestDescription: string): Promise<unknown> {
  try {
    return await response.json();
  } catch (error) {
    throw new RelayRequestError(
      "MCP_RELAY_REQUEST_FAILED",
      `Failed to parse ${requestDescription} JSON`,
      false,
      {
        cause: error instanceof Error ? error.message : String(error),
      },
    );
  }
}

function buildDownloadedImageMetadata(response: Response, bytes: Uint8Array): ImageDownloadResponse["image"] {
  const mimeType = toImageMimeType(response.headers.get("content-type"));
  const metadata: ImageDownloadResponse["image"] = {
    imageId: response.headers.get("x-image-id") ?? "",
    transferId: response.headers.get("x-transfer-id") ?? "",
    status: "UPLOADED",
    mimeType,
    size: bytes.byteLength,
    ...(response.headers.get("x-image-sha256") ? { sha256: response.headers.get("x-image-sha256") ?? undefined } : {}),
  };

  if (!Value.Check(ImageDownloadResponseSchema.properties.image, metadata)) {
    throw new RelayRequestError(
      "MCP_RELAY_RESPONSE_INVALID",
      "Relay image download headers did not match the shared protocol",
      false,
    );
  }

  return metadata;
}

function normalizeMimeType(value: string | null): string {
  const mimeType = value?.split(";")[0]?.trim();
  return mimeType ?? "";
}

function toImageMimeType(value: string | null): ImageDownloadResponse["image"]["mimeType"] {
  const mimeType = normalizeMimeType(value);
  if (mimeType !== DEFAULT_IMAGE_CONTENT_TYPE) {
    throw new RelayRequestError(
      "MCP_RELAY_RESPONSE_INVALID",
      "Relay image download returned an unexpected content type",
      false,
    );
  }

  return mimeType;
}
