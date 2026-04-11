import { afterEach, describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Elysia } from "elysia";

import { createRelayHttpAuthMiddleware } from "../lib/auth-middleware.ts";
import { DefaultCommandIdGenerator } from "../modules/command/id-generator.ts";
import { LocalFileStorage } from "../modules/image/file-storage.ts";
import { ImageService } from "../modules/image/image-service.ts";
import { createHttpImagesRoutes } from "./http-images.ts";

function createDeterministicIdGenerator() {
  let value = 0;
  return new DefaultCommandIdGenerator({
    randomUUID: () => `00000000-0000-4000-8000-${String(++value).padStart(12, "0")}`,
  });
}

function createJpegBytes() {
  return Uint8Array.from([0xff, 0xd8, 0xff, 0xdb, 0x00, 0x43, 0x00, 0xff, 0xd9]);
}

function createSha256(bytes: Uint8Array) {
  return createHash("sha256").update(bytes).digest("hex");
}

const tempDirs: string[] = [];

afterEach(async () => {
  while (tempDirs.length > 0) {
    const dir = tempDirs.pop();
    if (dir) {
      await rm(dir, { recursive: true, force: true });
    }
  }
});

describe("image upload routes", () => {
  test("image upload accepts a single-use JPEG upload and image download serves metadata and bytes", async () => {
    const dir = await mkdtemp(join(tmpdir(), "rokid-http-images-"));
    tempDirs.push(dir);

    const imageService = new ImageService({
      clock: () => 1_700_000_000_000,
      fileStorage: new LocalFileStorage({ rootDir: dir }),
      idGenerator: createDeterministicIdGenerator(),
    });
    const app = createHttpImagesRoutes({ imageService });
    const reservation = imageService.reserve({
      requestId: "req_abc123",
      deviceId: "rokid_glasses_01",
    });
    const bytes = createJpegBytes();
    const sha256 = createSha256(bytes);

    const uploadResponse = await app.handle(
      new Request(
        `http://localhost/api/v1/images/${reservation.imageId}?uploadToken=${reservation.uploadToken}`,
        {
          method: "PUT",
          headers: {
            "content-type": "image/jpeg",
            "content-length": String(bytes.byteLength),
            "x-device-id": "rokid_glasses_01",
            "x-request-id": "req_abc123",
            "x-upload-checksum-sha256": sha256,
          },
          body: bytes,
        },
      ),
    );

    expect(uploadResponse.status).toBe(200);
    expect(await uploadResponse.json()).toEqual({
      ok: true,
      image: {
        imageId: reservation.imageId,
        transferId: reservation.transferId,
        status: "UPLOADED",
        mimeType: "image/jpeg",
        size: bytes.byteLength,
        sha256,
        uploadedAt: 1_700_000_000_000,
      },
      timestamp: 1_700_000_000_000,
    });

    const duplicateUpload = await app.handle(
      new Request(
        `http://localhost/api/v1/images/${reservation.imageId}?uploadToken=${reservation.uploadToken}`,
        {
          method: "PUT",
          headers: {
            "content-type": "image/jpeg",
            "content-length": String(bytes.byteLength),
            "x-device-id": "rokid_glasses_01",
            "x-request-id": "req_abc123",
          },
          body: bytes,
        },
      ),
    );
    expect(duplicateUpload.status).toBe(409);
    expect((await duplicateUpload.json()).error.code).toBe("IMAGE_ALREADY_UPLOADED");

    const metadataResponse = await app.handle(
      new Request(`http://localhost/api/v1/images/${reservation.imageId}`, {
        headers: { accept: "application/json" },
      }),
    );
    expect(metadataResponse.status).toBe(200);
    expect(await metadataResponse.json()).toEqual({
      ok: true,
      image: {
        imageId: reservation.imageId,
        transferId: reservation.transferId,
        status: "UPLOADED",
        mimeType: "image/jpeg",
        size: bytes.byteLength,
        sha256,
      },
      timestamp: 1_700_000_000_000,
    });

    const downloadResponse = await app.handle(
      new Request(`http://localhost/api/v1/images/${reservation.imageId}`),
    );
    expect(downloadResponse.status).toBe(200);
    expect(downloadResponse.headers.get("content-type")).toBe("image/jpeg");
    expect(downloadResponse.headers.get("x-image-id")).toBe(reservation.imageId);
    expect(downloadResponse.headers.get("x-transfer-id")).toBe(reservation.transferId);
    expect(new Uint8Array(await downloadResponse.arrayBuffer())).toEqual(bytes);
  });

  test("image upload rejects invalid upload tokens", async () => {
    const dir = await mkdtemp(join(tmpdir(), "rokid-http-images-"));
    tempDirs.push(dir);

    const imageService = new ImageService({
      clock: () => 1_700_000_000_000,
      fileStorage: new LocalFileStorage({ rootDir: dir }),
      idGenerator: createDeterministicIdGenerator(),
    });
    const app = new Elysia()
      .use(createRelayHttpAuthMiddleware({ httpAuthTokens: ["mcp-token-1"] }))
      .use(createHttpImagesRoutes({ imageService }));
    const reservation = imageService.reserve({
      requestId: "req_abc123",
      deviceId: "rokid_glasses_01",
    });
    const bytes = createJpegBytes();

    const response = await app.handle(
      new Request(
        `http://localhost/api/v1/images/${reservation.imageId}?uploadToken=upl_invalid_invalid_invalid`,
        {
        method: "PUT",
        headers: {
          "content-type": "image/jpeg",
          "content-length": String(bytes.byteLength),
          "x-device-id": "rokid_glasses_01",
          "x-request-id": "req_abc123",
        },
        body: bytes,
        },
      ),
    );

    expect(response.status).toBe(403);
    expect((await response.json()).error.code).toBe("AUTH_UPLOAD_TOKEN_INVALID");
  });

  test("image upload bypasses bearer auth and still validates request headers", async () => {
    const dir = await mkdtemp(join(tmpdir(), "rokid-http-images-"));
    tempDirs.push(dir);

    const imageService = new ImageService({
      clock: () => 1_700_000_000_000,
      fileStorage: new LocalFileStorage({ rootDir: dir }),
      idGenerator: createDeterministicIdGenerator(),
    });
    const app = new Elysia()
      .use(createRelayHttpAuthMiddleware({ httpAuthTokens: ["mcp-token-1"] }))
      .use(createHttpImagesRoutes({ imageService }));
    const reservation = imageService.reserve({
      requestId: "req_abc123",
      deviceId: "rokid_glasses_01",
    });
    const bytes = createJpegBytes();

    const response = await app.handle(
      new Request(
        `http://localhost/api/v1/images/${reservation.imageId}?uploadToken=${reservation.uploadToken}`,
        {
          method: "PUT",
          headers: {
            "content-type": "image/jpeg",
          },
          body: bytes,
        },
      ),
    );

    expect(response.status).toBe(400);
    expect((await response.json()).error.code).toBe("IMAGE_UPLOAD_REQUEST_INVALID");
  });
});

describe("image download routes", () => {
  test("rejects missing or invalid bearer auth before image lookup runs", async () => {
    let metadataCalls = 0;
    let downloadCalls = 0;

    const app = new Elysia()
      .use(createRelayHttpAuthMiddleware({ httpAuthTokens: ["mcp-token-1"] }))
      .use(
        createHttpImagesRoutes({
          imageService: {
            getImageDownload() {
              downloadCalls += 1;
              throw new Error("download should not run");
            },
            getImageDownloadMetadata() {
              metadataCalls += 1;
              throw new Error("metadata should not run");
            },
          } as unknown as ImageService,
        }),
      );

    for (const headers of [undefined, { authorization: "Bearer wrong-token" }]) {
      const response = await app.handle(new Request("http://localhost/api/v1/images/img_missing123", { headers }));
      const json = await response.json();

      expect(response.status).toBe(401);
      expect(json.error.code).toBe("AUTH_HTTP_BEARER_INVALID");
    }

    expect(metadataCalls).toBe(0);
    expect(downloadCalls).toBe(0);
  });

  test("image download returns not found for unknown images", async () => {
    const dir = await mkdtemp(join(tmpdir(), "rokid-http-images-"));
    tempDirs.push(dir);

    const app = createHttpImagesRoutes({
      imageService: new ImageService({
        clock: () => 1_700_000_000_000,
        fileStorage: new LocalFileStorage({ rootDir: dir }),
      }),
    });

    const response = await app.handle(new Request("http://localhost/api/v1/images/img_missing123"));

    expect(response.status).toBe(404);
    expect((await response.json()).error.code).toBe("IMAGE_NOT_FOUND");
  });
});
