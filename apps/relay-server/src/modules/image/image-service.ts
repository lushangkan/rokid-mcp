import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";

import {
  DEFAULT_COMMAND_TIMEOUT_MS,
  DEFAULT_IMAGE_CONTENT_TYPE,
  DEFAULT_MAX_IMAGE_UPLOAD_SIZE_BYTES,
  DEFAULT_SESSION_TTL_MS,
  type CommandDispatchImage,
  type DeviceId,
  type ImageContentType,
  type ImageDownloadResponse,
  type ImageId,
  type ImageStatus,
  type ImageUploadHeaders,
  type ImageUploadResponse,
  type RequestId,
  type TransferId,
} from "@rokid-mcp/protocol";

import {
  DefaultCommandIdGenerator,
  type CommandIdGenerator,
} from "../command/id-generator.ts";
import { LocalFileStorage, type FileStorage, type PersistedImageFile } from "./file-storage.ts";
import { ImageStore, type StoredImageRecord } from "./image-store.ts";
import { UploadTokenManager } from "./upload-token-manager.ts";

type Clock = () => number;

export type CommandImageState = {
  imageId: ImageId;
  transferId: TransferId;
  status: ImageStatus;
  mimeType: ImageContentType;
  size?: number;
  sha256?: string;
  uploadedAt?: number;
};

export type ReserveImageInput = {
  requestId: RequestId;
  deviceId: DeviceId;
};

export type UploadImageInput = {
  imageId: ImageId;
  uploadToken: string;
  headers: ImageUploadHeaders;
  bytes: Uint8Array;
};

export type DownloadImagePayload = {
  metadata: ImageDownloadResponse;
  bytes: Uint8Array;
};

type FailPendingImageInput = {
  imageId: ImageId;
  requestId: RequestId;
  deviceId: DeviceId;
  transferId: TransferId;
};

type ImageServiceDependencies = {
  clock?: Clock;
  cleanupIntervalMs?: number;
  failedRetentionMs?: number;
  fileStorage?: FileStorage;
  idGenerator?: CommandIdGenerator;
  maxUploadSizeBytes?: number;
  store?: ImageStore;
  tokenManager?: UploadTokenManager;
  uploadTtlMs?: number;
  uploadedRetentionMs?: number;
};

const DEFAULT_CLEANUP_INTERVAL_MS = 30_000;
const DEFAULT_IMAGE_DATA_DIR = fileURLToPath(new URL("../../../data/images", import.meta.url));

function createImageServiceError(code: string, message: string, retryable = false): ImageServiceError {
  return new ImageServiceError({ code, message, retryable });
}

function toCommandImageState(record: StoredImageRecord): CommandImageState {
  return {
    imageId: record.imageId,
    transferId: record.transferId,
    status: record.status,
    mimeType: record.mimeType,
    size: record.size,
    sha256: record.sha256,
    uploadedAt: record.uploadedAt,
  };
}

export class ImageService {
  private readonly cleanupIntervalMs: number;
  private readonly clock: Clock;
  private readonly failedRetentionMs: number;
  private readonly fileStorage: FileStorage;
  private readonly idGenerator: CommandIdGenerator;
  private readonly maxUploadSizeBytes: number;
  private readonly store: ImageStore;
  private readonly tokenManager: UploadTokenManager;
  private readonly uploadTtlMs: number;
  private readonly uploadedRetentionMs: number;
  private cleanupTimer: ReturnType<typeof setInterval> | null = null;

  constructor(dependencies: ImageServiceDependencies = {}) {
    this.cleanupIntervalMs = dependencies.cleanupIntervalMs ?? DEFAULT_CLEANUP_INTERVAL_MS;
    this.clock = dependencies.clock ?? (() => Date.now());
    this.failedRetentionMs = dependencies.failedRetentionMs ?? DEFAULT_COMMAND_TIMEOUT_MS;
    this.fileStorage = dependencies.fileStorage ?? new LocalFileStorage({ rootDir: DEFAULT_IMAGE_DATA_DIR });
    this.idGenerator = dependencies.idGenerator ?? new DefaultCommandIdGenerator();
    this.maxUploadSizeBytes = dependencies.maxUploadSizeBytes ?? DEFAULT_MAX_IMAGE_UPLOAD_SIZE_BYTES;
    this.store = dependencies.store ?? new ImageStore();
    this.tokenManager = dependencies.tokenManager ?? new UploadTokenManager();
    this.uploadTtlMs = dependencies.uploadTtlMs ?? DEFAULT_COMMAND_TIMEOUT_MS;
    this.uploadedRetentionMs = dependencies.uploadedRetentionMs ?? DEFAULT_SESSION_TTL_MS;
  }

  reserve(input: ReserveImageInput): CommandDispatchImage {
    const createdAt = this.clock();
    const expiresAt = createdAt + this.uploadTtlMs;
    const imageId = this.idGenerator.createImageId() as ImageId;
    const transferId = this.idGenerator.createTransferId() as TransferId;
    const uploadToken = this.idGenerator.createUploadToken();

    this.store.save({
      imageId,
      requestId: input.requestId,
      deviceId: input.deviceId,
      transferId,
      status: "RESERVED",
      mimeType: DEFAULT_IMAGE_CONTENT_TYPE,
      maxSizeBytes: this.maxUploadSizeBytes,
      uploadTokenHash: this.tokenManager.createTokenHash(uploadToken),
      expiresAt,
      createdAt,
      updatedAt: createdAt,
    });

    return {
      imageId,
      transferId,
      uploadToken,
      contentType: DEFAULT_IMAGE_CONTENT_TYPE,
      expiresAt,
      maxSizeBytes: this.maxUploadSizeBytes,
    };
  }

  async uploadImage(input: UploadImageInput): Promise<ImageUploadResponse> {
    const uploadingRecord = this.beginUpload(input);
    let persistedFile: PersistedImageFile | null = null;

    try {
      if (input.bytes.byteLength === 0) {
        throw createImageServiceError("UPLOAD_EMPTY_BODY", "Image upload body cannot be empty.");
      }

      if (input.bytes.byteLength > uploadingRecord.maxSizeBytes) {
        throw createImageServiceError("IMAGE_TOO_LARGE", "Image upload exceeds the relay size limit.");
      }

      if (
        typeof input.headers.contentLength === "number" &&
        input.headers.contentLength !== input.bytes.byteLength
      ) {
        throw createImageServiceError(
          "UPLOAD_CONTENT_LENGTH_MISMATCH",
          "Image upload body length does not match the declared content length.",
        );
      }

      const sha256 = createHash("sha256").update(input.bytes).digest("hex");
      if (input.headers.sha256 && input.headers.sha256 !== sha256) {
        throw createImageServiceError("CHECKSUM_MISMATCH", "Image upload checksum does not match the payload.");
      }

      persistedFile = await this.fileStorage.saveImage({
        imageId: input.imageId,
        bytes: input.bytes,
      });

      return this.completeUpload(uploadingRecord.imageId, persistedFile, sha256);
    } catch (error) {
      if (persistedFile) {
        await this.fileStorage.deleteImage(persistedFile.filePath);
      }

      this.failPendingImage({
        imageId: uploadingRecord.imageId,
        requestId: uploadingRecord.requestId,
        deviceId: uploadingRecord.deviceId,
        transferId: uploadingRecord.transferId,
      });
      throw this.normalizeUploadError(error);
    }
  }

  async getImageDownload(imageId: ImageId): Promise<DownloadImagePayload> {
    const readable = this.requireUploadedRecord(imageId);
    const filePath = readable.filePath;
    if (!filePath) {
      throw createImageServiceError("IMAGE_NOT_READY", `Image '${imageId}' is missing a readable file path.`);
    }

    const bytes = await this.fileStorage.readImage(filePath);

    return {
      metadata: this.createDownloadResponse(readable),
      bytes,
    };
  }

  getImageDownloadMetadata(imageId: ImageId): ImageDownloadResponse {
    return this.createDownloadResponse(this.requireUploadedRecord(imageId));
  }

  getImageState(input: FailPendingImageInput): CommandImageState | null {
    const record = this.store.get(input.imageId);
    if (!record || !this.matchesReservation(record, input)) {
      return null;
    }

    return toCommandImageState(record);
  }

  failPendingImage(input: FailPendingImageInput): void {
    const current = this.store.get(input.imageId);
    if (!current || !this.matchesReservation(current, input)) {
      return;
    }

    if (current.status === "UPLOADED" || current.status === "FAILED") {
      return;
    }

    const now = this.clock();
    this.store.update(input.imageId, (record) => ({
      ...record,
      status: "FAILED",
      updatedAt: now,
      filePath: undefined,
      size: undefined,
      sha256: undefined,
      uploadedAt: undefined,
    }));
  }

  startCleanupJob(): void {
    if (this.cleanupTimer) {
      return;
    }

    this.cleanupTimer = setInterval(() => {
      void this.cleanup(Date.now());
    }, this.cleanupIntervalMs);
  }

  stopCleanupJob(): void {
    if (!this.cleanupTimer) {
      return;
    }

    clearInterval(this.cleanupTimer);
    this.cleanupTimer = null;
  }

  async cleanup(now = this.clock()): Promise<void> {
    for (const record of this.store.list()) {
      if (record.status === "RESERVED" && record.expiresAt <= now) {
        this.store.delete(record.imageId);
        continue;
      }

      if (record.status === "FAILED" && record.updatedAt + this.failedRetentionMs <= now) {
        await this.deleteStoredRecord(record);
        continue;
      }

      if (
        record.status === "UPLOADED" &&
        typeof record.uploadedAt === "number" &&
        record.uploadedAt + this.uploadedRetentionMs <= now
      ) {
        await this.deleteStoredRecord(record);
      }
    }
  }

  private beginUpload(input: UploadImageInput): StoredImageRecord {
    const current = this.store.get(input.imageId);
    if (!current) {
      throw createImageServiceError("IMAGE_NOT_FOUND", `Image '${input.imageId}' was not reserved.`);
    }

    if (current.status === "UPLOADING") {
      throw createImageServiceError("IMAGE_UPLOAD_IN_PROGRESS", "Image upload is already in progress.");
    }

    if (current.status === "UPLOADED") {
      throw createImageServiceError("IMAGE_ALREADY_UPLOADED", "Image has already been uploaded.");
    }

    if (current.status === "FAILED") {
      throw createImageServiceError("IMAGE_UPLOAD_REJECTED", "Image reservation is no longer uploadable.");
    }

    if (current.expiresAt <= this.clock()) {
      this.failPendingImage({
        imageId: current.imageId,
        requestId: current.requestId,
        deviceId: current.deviceId,
        transferId: current.transferId,
      });
      throw createImageServiceError("IMAGE_EXPIRED", "Image upload token has expired.");
    }

    if (!this.tokenManager.matches(input.uploadToken, current.uploadTokenHash)) {
      throw createImageServiceError("AUTH_UPLOAD_TOKEN_INVALID", "Image upload token is invalid.");
    }

    if (input.headers.deviceId !== current.deviceId || input.headers.requestId !== current.requestId) {
      throw createImageServiceError(
        "AUTH_UPLOAD_TOKEN_MISMATCH",
        "Image upload metadata does not match the reserved device or request.",
      );
    }

    if (input.headers.contentType !== current.mimeType) {
      throw createImageServiceError(
        "INVALID_IMAGE_CONTENT_TYPE",
        `Relay only accepts '${DEFAULT_IMAGE_CONTENT_TYPE}' image uploads.`,
      );
    }

    if (
      typeof input.headers.contentLength === "number" &&
      input.headers.contentLength > current.maxSizeBytes
    ) {
      throw createImageServiceError("IMAGE_TOO_LARGE", "Image upload exceeds the relay size limit.");
    }

    const startedAt = this.clock();
    const updated = this.store.update(input.imageId, (record) => ({
      ...record,
      status: "UPLOADING",
      updatedAt: startedAt,
    }));

    if (!updated) {
      throw createImageServiceError("IMAGE_NOT_FOUND", `Image '${input.imageId}' was not reserved.`);
    }

    return updated;
  }

  private completeUpload(
    imageId: ImageId,
    persistedFile: PersistedImageFile,
    sha256: string,
  ): ImageUploadResponse {
    const uploadedAt = this.clock();
    const updated = this.store.update(imageId, (record) => ({
      ...record,
      status: "UPLOADED",
      updatedAt: uploadedAt,
      size: persistedFile.size,
      sha256,
      filePath: persistedFile.filePath,
      uploadedAt,
    }));

    if (!updated) {
      throw createImageServiceError("IMAGE_NOT_FOUND", `Image '${imageId}' was not reserved.`);
    }

    return {
      ok: true,
      image: {
        imageId: updated.imageId,
        transferId: updated.transferId,
        status: "UPLOADED",
        mimeType: updated.mimeType,
        size: updated.size ?? persistedFile.size,
        sha256: updated.sha256,
        uploadedAt,
      },
      timestamp: uploadedAt,
    };
  }

  private createDownloadResponse(record: StoredImageRecord): ImageDownloadResponse {
    const now = this.clock();
    return {
      ok: true,
      image: {
        imageId: record.imageId,
        transferId: record.transferId,
        status: "UPLOADED",
        mimeType: record.mimeType,
        size: record.size ?? this.raiseUnreadableImage(record.imageId),
        sha256: record.sha256,
      },
      timestamp: now,
    };
  }

  private requireUploadedRecord(imageId: ImageId): StoredImageRecord {
    const record = this.store.get(imageId);
    if (!record) {
      throw createImageServiceError("IMAGE_NOT_FOUND", `Image '${imageId}' does not exist.`);
    }

    if (record.status !== "UPLOADED" || !record.filePath) {
      throw createImageServiceError("IMAGE_NOT_READY", `Image '${imageId}' is not available for download yet.`);
    }

    return record;
  }

  private raiseUnreadableImage(imageId: ImageId): never {
    throw createImageServiceError("IMAGE_NOT_READY", `Image '${imageId}' is missing persisted metadata.`);
  }

  private matchesReservation(
    record: StoredImageRecord,
    input: FailPendingImageInput,
  ): boolean {
    return (
      record.requestId === input.requestId &&
      record.deviceId === input.deviceId &&
      record.transferId === input.transferId
    );
  }

  private async deleteStoredRecord(record: StoredImageRecord): Promise<void> {
    if (record.filePath) {
      await this.fileStorage.deleteImage(record.filePath);
    }

    this.store.delete(record.imageId);
  }

  private normalizeUploadError(error: unknown): ImageServiceError {
    if (error instanceof ImageServiceError) {
      return error;
    }

    return createImageServiceError("UPLOAD_STORAGE_FAILED", "Relay failed to persist the uploaded image.", true);
  }
}

export class ImageServiceError extends Error {
  readonly code: string;
  readonly retryable: boolean;

  constructor(options: { code: string; message: string; retryable: boolean }) {
    super(options.message);
    this.name = "ImageServiceError";
    this.code = options.code;
    this.retryable = options.retryable;
  }
}
