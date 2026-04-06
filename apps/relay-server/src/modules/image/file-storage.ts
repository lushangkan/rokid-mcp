import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { basename, join } from "node:path";

import type { ImageId } from "@rokid-mcp/protocol";

export type PersistedImageFile = {
  filePath: string;
  size: number;
};

export type FileStorage = {
  saveImage(input: { imageId: ImageId; bytes: Uint8Array }): Promise<PersistedImageFile>;
  readImage(filePath: string): Promise<Uint8Array>;
  deleteImage(filePath: string): Promise<void>;
};

type LocalFileStorageOptions = {
  rootDir: string;
};

export class LocalFileStorage implements FileStorage {
  private readonly rootDir: string;

  constructor(options: LocalFileStorageOptions) {
    this.rootDir = options.rootDir;
  }

  async saveImage(input: { imageId: ImageId; bytes: Uint8Array }): Promise<PersistedImageFile> {
    await mkdir(this.rootDir, { recursive: true });

    const filePath = this.getFinalPath(input.imageId);
    const tempPath = join(
      this.rootDir,
      `${input.imageId}.${crypto.randomUUID().replace(/-/g, "_")}.tmp`,
    );

    try {
      await writeFile(tempPath, input.bytes);
      await rename(tempPath, filePath);

      return {
        filePath,
        size: input.bytes.byteLength,
      };
    } catch (error) {
      await rm(tempPath, { force: true });
      throw error;
    }
  }

  async readImage(filePath: string): Promise<Uint8Array> {
    return readFile(filePath);
  }

  async deleteImage(filePath: string): Promise<void> {
    await rm(filePath, { force: true });
  }

  getDebugFileName(filePath: string): string {
    return basename(filePath);
  }

  private getFinalPath(imageId: ImageId): string {
    return join(this.rootDir, `${imageId}.jpg`);
  }
}
