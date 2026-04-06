import type { DeviceId, ImageContentType, ImageId, ImageStatus, RequestId, TransferId } from "@rokid-mcp/protocol";

export type StoredImageRecord = {
  imageId: ImageId;
  requestId: RequestId;
  deviceId: DeviceId;
  transferId: TransferId;
  status: ImageStatus;
  mimeType: ImageContentType;
  maxSizeBytes: number;
  uploadTokenHash: string;
  expiresAt: number;
  createdAt: number;
  updatedAt: number;
  size?: number;
  sha256?: string;
  filePath?: string;
  uploadedAt?: number;
};

function cloneRecord(record: StoredImageRecord): StoredImageRecord {
  return structuredClone(record);
}

export class ImageStore {
  private readonly recordsByImageId = new Map<ImageId, StoredImageRecord>();

  get(imageId: ImageId): StoredImageRecord | null {
    const record = this.recordsByImageId.get(imageId);
    return record ? cloneRecord(record) : null;
  }

  save(record: StoredImageRecord): StoredImageRecord {
    const next = cloneRecord(record);
    this.recordsByImageId.set(next.imageId, next);
    return cloneRecord(next);
  }

  update(
    imageId: ImageId,
    updater: (current: StoredImageRecord) => StoredImageRecord,
  ): StoredImageRecord | null {
    const current = this.recordsByImageId.get(imageId);
    if (!current) {
      return null;
    }

    return this.save(updater(cloneRecord(current)));
  }

  delete(imageId: ImageId): void {
    this.recordsByImageId.delete(imageId);
  }

  list(): StoredImageRecord[] {
    return [...this.recordsByImageId.values()].map((record) => cloneRecord(record));
  }

  clear(): void {
    this.recordsByImageId.clear();
  }
}
