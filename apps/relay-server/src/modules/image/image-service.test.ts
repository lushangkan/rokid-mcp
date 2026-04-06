import { afterEach, describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdtemp, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { DefaultCommandIdGenerator } from "../command/id-generator.ts";
import { LocalFileStorage } from "./file-storage.ts";
import { ImageService, ImageServiceError } from "./image-service.ts";

function createDeterministicIdGenerator() {
  let value = 0;
  return new DefaultCommandIdGenerator({
    randomUUID: () => `00000000-0000-4000-8000-${String(++value).padStart(12, "0")}`,
  });
}

async function createTempDir() {
  return mkdtemp(join(tmpdir(), "rokid-relay-images-"));
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

describe("image manager", () => {
  test("image manager reserves, uploads, and returns relay-owned image metadata", async () => {
    const dir = await createTempDir();
    tempDirs.push(dir);

    const service = new ImageService({
      clock: () => 1_700_000_000_000,
      fileStorage: new LocalFileStorage({ rootDir: dir }),
      idGenerator: createDeterministicIdGenerator(),
    });
    const bytes = createJpegBytes();
    const sha256 = createSha256(bytes);

    const reservation = service.reserve({
      requestId: "req_abc123",
      deviceId: "rokid_glasses_01",
    });
    const upload = await service.uploadImage({
      imageId: reservation.imageId,
      uploadToken: reservation.uploadToken,
      headers: {
        contentType: "image/jpeg",
        contentLength: bytes.byteLength,
        deviceId: "rokid_glasses_01",
        requestId: "req_abc123",
        sha256,
      },
      bytes,
    });

    expect(upload.image).toEqual({
      imageId: reservation.imageId,
      transferId: reservation.transferId,
      status: "UPLOADED",
      mimeType: "image/jpeg",
      size: bytes.byteLength,
      sha256,
      uploadedAt: 1_700_000_000_000,
    });

    const metadata = service.getImageDownloadMetadata(reservation.imageId);
    const download = await service.getImageDownload(reservation.imageId);

    expect(metadata.image).toEqual({
      imageId: reservation.imageId,
      transferId: reservation.transferId,
      status: "UPLOADED",
      mimeType: "image/jpeg",
      size: bytes.byteLength,
      sha256,
    });
    expect([...download.bytes]).toEqual([...bytes]);
    expect(await readdir(dir)).toEqual([`${reservation.imageId}.jpg`]);
  });

  test("image manager rejects checksum mismatches without orphaned files", async () => {
    const dir = await createTempDir();
    tempDirs.push(dir);

    const service = new ImageService({
      clock: () => 1_700_000_000_000,
      fileStorage: new LocalFileStorage({ rootDir: dir }),
      idGenerator: createDeterministicIdGenerator(),
    });
    const reservation = service.reserve({
      requestId: "req_abc123",
      deviceId: "rokid_glasses_01",
    });
    const bytes = createJpegBytes();

    await expect(
      service.uploadImage({
        imageId: reservation.imageId,
        uploadToken: reservation.uploadToken,
        headers: {
          contentType: "image/jpeg",
          contentLength: bytes.byteLength,
          deviceId: "rokid_glasses_01",
          requestId: "req_abc123",
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
        bytes,
      }),
    ).rejects.toMatchObject({
      name: "ImageServiceError",
      code: "CHECKSUM_MISMATCH",
    } satisfies Partial<ImageServiceError>);

    expect(
      service.getImageState({
        imageId: reservation.imageId,
        requestId: "req_abc123",
        deviceId: "rokid_glasses_01",
        transferId: reservation.transferId,
      }),
    ).toEqual({
      imageId: reservation.imageId,
      transferId: reservation.transferId,
      status: "FAILED",
      mimeType: "image/jpeg",
      size: undefined,
      sha256: undefined,
      uploadedAt: undefined,
    });
    expect(await readdir(dir)).toEqual([]);
  });

  test("image manager cleanup expires stale reservations and uploaded files", async () => {
    let now = 1_700_000_000_000;
    const dir = await createTempDir();
    tempDirs.push(dir);

    const service = new ImageService({
      clock: () => now,
      fileStorage: new LocalFileStorage({ rootDir: dir }),
      idGenerator: createDeterministicIdGenerator(),
      uploadTtlMs: 10,
      uploadedRetentionMs: 10,
    });

    const staleReservation = service.reserve({
      requestId: "req_abc123",
      deviceId: "rokid_glasses_01",
    });

    now += 11;
    await service.cleanup(now);

    expect(
      service.getImageState({
        imageId: staleReservation.imageId,
        requestId: "req_abc123",
        deviceId: "rokid_glasses_01",
        transferId: staleReservation.transferId,
      }),
    ).toBeNull();

    const uploadedReservation = service.reserve({
      requestId: "req_def456",
      deviceId: "rokid_glasses_01",
    });
    const bytes = createJpegBytes();

    await service.uploadImage({
      imageId: uploadedReservation.imageId,
      uploadToken: uploadedReservation.uploadToken,
      headers: {
        contentType: "image/jpeg",
        contentLength: bytes.byteLength,
        deviceId: "rokid_glasses_01",
        requestId: "req_def456",
      },
      bytes,
    });

    now += 11;
    await service.cleanup(now);

    expect(await readdir(dir)).toEqual([]);
    expect(() => service.getImageDownloadMetadata(uploadedReservation.imageId)).toThrow(
      "does not exist.",
    );
  });
});
