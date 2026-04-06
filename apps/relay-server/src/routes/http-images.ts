import {
  ImageIdSchema,
  ImageUploadHeadersSchema,
  UploadTokenSchema,
  type ErrorResponse,
  type ImageDownloadResponse,
  type ImageUploadHeaders,
} from "@rokid-mcp/protocol";
import { Value } from "@sinclair/typebox/value";
import { Elysia } from "elysia";

import { ImageService, ImageServiceError } from "../modules/image/image-service.ts";

type HttpImagesRoutesOptions = {
  imageService: ImageService;
};

function createErrorResponse(error: ImageServiceError): ErrorResponse {
  return {
    ok: false,
    error: {
      code: error.code,
      message: error.message,
      retryable: error.retryable,
    },
    timestamp: Date.now(),
  };
}

function getHttpStatus(error: ImageServiceError): number {
  switch (error.code) {
    case "IMAGE_NOT_FOUND":
      return 404;
    case "IMAGE_NOT_READY":
    case "IMAGE_UPLOAD_IN_PROGRESS":
    case "IMAGE_ALREADY_UPLOADED":
    case "IMAGE_UPLOAD_REJECTED":
      return 409;
    case "IMAGE_EXPIRED":
      return 410;
    case "AUTH_UPLOAD_TOKEN_INVALID":
    case "AUTH_UPLOAD_TOKEN_MISMATCH":
      return 403;
    case "INVALID_IMAGE_CONTENT_TYPE":
      return 415;
    case "IMAGE_TOO_LARGE":
      return 413;
    case "UPLOAD_STORAGE_FAILED":
      return 500;
    default:
      return 400;
  }
}

function parseImageUploadHeaders(request: Request): ImageUploadHeaders | null {
  const contentLength = request.headers.get("content-length");
  const parsedHeaders = {
    contentType: request.headers.get("content-type"),
    contentLength: contentLength ? Number(contentLength) : undefined,
    deviceId: request.headers.get("x-device-id"),
    requestId: request.headers.get("x-request-id"),
    sha256: request.headers.get("x-upload-checksum-sha256") ?? undefined,
  };

  return Value.Check(ImageUploadHeadersSchema, parsedHeaders) ? parsedHeaders : null;
}

function prefersJsonResponse(request: Request): boolean {
  const accept = request.headers.get("accept") ?? "";
  return accept.includes("application/json") && !accept.includes("image/jpeg");
}

function applyDownloadHeaders(response: Response, metadata: ImageDownloadResponse): Response {
  response.headers.set("cache-control", "private, max-age=300");
  response.headers.set("content-length", String(metadata.image.size));
  response.headers.set("content-type", metadata.image.mimeType);
  response.headers.set("x-image-id", metadata.image.imageId);
  response.headers.set("x-transfer-id", metadata.image.transferId);

  if (metadata.image.sha256) {
    response.headers.set("etag", `\"${metadata.image.sha256}\"`);
    response.headers.set("x-image-sha256", metadata.image.sha256);
  }

  return response;
}

export function createHttpImagesRoutes(options: HttpImagesRoutesOptions) {
  return new Elysia({ name: "http-images-routes" })
    .put("/api/v1/images/:imageId", async ({ params, query, request, set }) => {
      const imageId = (params as { imageId: string }).imageId;
      const uploadToken = (query as { uploadToken?: string }).uploadToken;

      if (!Value.Check(ImageIdSchema, imageId) || !Value.Check(UploadTokenSchema, uploadToken)) {
        set.status = 422;
        return {
          ok: false,
          error: {
            code: "IMAGE_UPLOAD_REQUEST_INVALID",
            message: "Image upload path or token failed protocol validation.",
            retryable: false,
          },
          timestamp: Date.now(),
        } satisfies ErrorResponse;
      }

      const headers = parseImageUploadHeaders(request);

      if (!headers) {
        set.status = 400;
        return {
          ok: false,
          error: {
            code: "IMAGE_UPLOAD_REQUEST_INVALID",
            message: "Image upload request is missing required token or headers.",
            retryable: false,
          },
          timestamp: Date.now(),
        } satisfies ErrorResponse;
      }

      try {
        const bytes = new Uint8Array(await request.arrayBuffer());
        return await options.imageService.uploadImage({
          imageId,
          uploadToken,
          headers,
          bytes,
        });
      } catch (error) {
        const normalized =
          error instanceof ImageServiceError
            ? error
            : new ImageServiceError({
                code: "UPLOAD_STORAGE_FAILED",
                message: "Relay failed to persist the uploaded image.",
                retryable: true,
              });
        set.status = getHttpStatus(normalized);
        return createErrorResponse(normalized);
      }
    })
    .get("/api/v1/images/:imageId", async ({ params, request, set }) => {
      const imageId = (params as { imageId: string }).imageId;

      if (!Value.Check(ImageIdSchema, imageId)) {
        set.status = 422;
        return {
          ok: false,
          error: {
            code: "IMAGE_DOWNLOAD_REQUEST_INVALID",
            message: "Image id failed protocol validation.",
            retryable: false,
          },
          timestamp: Date.now(),
        } satisfies ErrorResponse;
      }

      try {
        if (prefersJsonResponse(request)) {
          return options.imageService.getImageDownloadMetadata(imageId);
        }

        const download = await options.imageService.getImageDownload(imageId);
        return applyDownloadHeaders(new Response(Buffer.from(download.bytes)), download.metadata);
      } catch (error) {
        const normalized =
          error instanceof ImageServiceError
            ? error
            : new ImageServiceError({
                code: "UPLOAD_STORAGE_FAILED",
                message: "Relay failed to read the stored image.",
                retryable: true,
              });
        set.status = getHttpStatus(normalized);
        return createErrorResponse(normalized);
      }
    });
}
