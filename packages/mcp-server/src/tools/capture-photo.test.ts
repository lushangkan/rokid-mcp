import { describe, expect, mock, test } from "bun:test";
import type { CommandRecord, GetCommandStatusResponse, SubmitCapturePhotoCommandResponse } from "@rokid-mcp/protocol";

import { createCommandPoller } from "../command/command-poller.js";
import { createImageDownloader } from "../image/image-downloader.js";
import { createDownloadedImageVerifier } from "../image/image-verifier.js";
import { RelayRequestError } from "../lib/errors.js";
import { createCapturePhotoResultMapper } from "../mapper/capture-photo-result-mapper.js";
import { createCapturePhotoUseCase } from "../usecases/capture-photo-usecase.js";
import { CAPTURE_PHOTO_TOOL_NAME, createCapturePhotoTool } from "./capture-photo.js";

const DEFAULT_DEVICE_ID = "rokid_glasses_01";

describe("capture photo tool", () => {
  test("capture photo tool returns base64 image output after the upload completes", async () => {
    const submitCommand = mock(async () => createCapturePhotoSubmission());
    const getCommandStatus = mock(async () =>
      createCommandStatusResponse(
        createCapturePhotoCommand("COMPLETED", {
          completedAt: 1_710_000_000_300,
          result: {
            action: "capture_photo",
            imageId: "img_photo123",
            transferId: "trf_photo123",
            mimeType: "image/jpeg",
            size: 3,
            width: 640,
            height: 480,
          },
          image: {
            imageId: "img_photo123",
            transferId: "trf_photo123",
            status: "UPLOADED",
            mimeType: "image/jpeg",
            size: 3,
            width: 640,
            height: 480,
            uploadedAt: 1_710_000_000_250,
          },
        }),
      ),
    );
    const waits: number[] = [];
    const poller = createCommandPoller({
      relayCommandClient: {
        submitCommand,
        getCommandStatus,
        downloadImage: async () => {
          throw new Error("not used by the poller");
        },
      },
      timeoutMs: 1_000,
      pollIntervalMs: 100,
      wait: async (delayMs) => {
        waits.push(delayMs);
      },
    });
    const download = mock(async () => ({
      imageId: "img_photo123",
      transferId: "trf_photo123",
      status: "UPLOADED" as const,
      mimeType: "image/jpeg" as const,
      size: 3,
      bytes: new Uint8Array([1, 2, 3]),
    }));
    const imageDownloader = createImageDownloader({
      relayCommandClient: {
        downloadImage: download,
      },
    });
    const useCase = createCapturePhotoUseCase({
      relayCommandClient: {
        submitCommand,
      },
      commandPoller: poller,
      imageDownloader,
      downloadedImageVerifier: createDownloadedImageVerifier(),
      resultMapper: createCapturePhotoResultMapper(),
    });
    const tool = createCapturePhotoTool({
      defaultDeviceId: DEFAULT_DEVICE_ID,
      useCase,
    });

    const result = await tool.handler({});

    expect(tool.name).toBe(CAPTURE_PHOTO_TOOL_NAME);
    expect(result.isError).toBeUndefined();
    expect(result.structuredContent).toEqual({
      requestId: "req_photo123",
      action: "capture_photo",
      completedAt: 1_710_000_000_300,
      image: {
        imageId: "img_photo123",
        mimeType: "image/jpeg",
        size: 3,
        width: 640,
        height: 480,
        dataBase64: "AQID",
      },
    });
    expect(submitCommand).toHaveBeenCalledWith({
      deviceId: DEFAULT_DEVICE_ID,
      action: "capture_photo",
      payload: {
        quality: "medium",
      },
    });
    expect(getCommandStatus).toHaveBeenCalledWith("req_photo123");
    expect(download).toHaveBeenCalledWith("img_photo123");
    expect(waits).toEqual([]);
  });

  test("capture photo tool returns MCP_INVALID_PARAMS for invalid input", async () => {
    const tool = createCapturePhotoTool({
      defaultDeviceId: DEFAULT_DEVICE_ID,
      useCase: {
        async execute() {
          throw new Error("should not be called");
        },
      },
    });

    const result = await tool.handler({ quality: "ultra" });

    expect(result.isError).toBe(true);
    expect(result.error?.code).toBe("MCP_INVALID_PARAMS");
  });

  test("capture photo tool returns terminal relay failures without downloading the image", async () => {
    const download = mock(async () => {
      throw new Error("should not download on failure");
    });
    const useCase = createCapturePhotoUseCase({
      relayCommandClient: {
        submitCommand: async () => createCapturePhotoSubmission(),
      },
      commandPoller: {
        async pollUntilTerminal() {
          return createCapturePhotoCommand("FAILED", {
            error: {
              code: "UPLOAD_FAILED",
              message: "The phone failed to upload the captured photo.",
              retryable: true,
            },
            image: {
              imageId: "img_photo123",
              transferId: "trf_photo123",
              status: "FAILED",
              mimeType: "image/jpeg",
            },
          });
        },
      },
      imageDownloader: createImageDownloader({
        relayCommandClient: {
          downloadImage: download,
        },
      }),
      downloadedImageVerifier: createDownloadedImageVerifier(),
      resultMapper: createCapturePhotoResultMapper(),
    });
    const tool = createCapturePhotoTool({
      defaultDeviceId: DEFAULT_DEVICE_ID,
      useCase,
    });

    const result = await tool.handler({ quality: "high" });

    expect(result.isError).toBe(true);
    expect(result.error).toMatchObject({
      code: "UPLOAD_FAILED",
      retryable: true,
    });
    expect(download).not.toHaveBeenCalled();
  });

  test("capture photo tool maps unexpected workflow errors to MCP_RELAY_REQUEST_FAILED", async () => {
    const tool = createCapturePhotoTool({
      defaultDeviceId: DEFAULT_DEVICE_ID,
      useCase: {
        async execute() {
          throw new Error("unexpected boom");
        },
      },
    });

    const result = await tool.handler({ quality: "low" });

    expect(result.isError).toBe(true);
    expect(result.error?.code).toBe("MCP_RELAY_REQUEST_FAILED");
    expect(result.error?.retryable).toBe(true);
  });
});

describe("image downloader", () => {
  test("image downloader validates non-empty bytes and expected metadata", async () => {
    const downloadImage = mock(async () => ({
      imageId: "img_photo123",
      transferId: "trf_photo123",
      status: "UPLOADED" as const,
      mimeType: "image/jpeg" as const,
      size: 4,
      sha256: "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
      bytes: new Uint8Array([1, 2, 3, 4]),
    }));
    const downloader = createImageDownloader({ relayCommandClient: { downloadImage } });

    const result = await downloader.download({
      imageId: "img_photo123",
      expectedSize: 4,
      expectedMimeType: "image/jpeg",
    });

    expect(result.bytes).toEqual(new Uint8Array([1, 2, 3, 4]));
    expect(downloadImage).toHaveBeenCalledWith("img_photo123");
  });

  test("image downloader converts size mismatches into MCP_IMAGE_DOWNLOAD_FAILED", async () => {
    const downloader = createImageDownloader({
      relayCommandClient: {
        async downloadImage() {
          return {
            imageId: "img_photo123",
            transferId: "trf_photo123",
            status: "UPLOADED" as const,
            mimeType: "image/jpeg" as const,
            size: 3,
            bytes: new Uint8Array([1, 2, 3]),
          };
        },
      },
    });

    try {
      await downloader.download({
        imageId: "img_photo123",
        expectedSize: 4,
        expectedMimeType: "image/jpeg",
      });
      throw new Error("expected image download to fail");
    } catch (error) {
      expect(error).toMatchObject({
        code: "MCP_IMAGE_DOWNLOAD_FAILED",
      });
    }
  });

  test("image downloader passes through relay image access errors", async () => {
    const downloader = createImageDownloader({
      relayCommandClient: {
        async downloadImage() {
          throw new RelayRequestError("IMAGE_NOT_FOUND", "Image not found", false);
        },
      },
    });

    try {
      await downloader.download({
        imageId: "img_missing123",
        expectedSize: 3,
        expectedMimeType: "image/jpeg",
      });
      throw new Error("expected image download to fail");
    } catch (error) {
      expect(error).toMatchObject({
        code: "IMAGE_NOT_FOUND",
      });
    }
  });
});

describe("image parser", () => {
  test("image parser verifies checksum metadata before returning the image bytes", () => {
    const verifier = createDownloadedImageVerifier();

    const result = verifier.verify({
      downloadedImage: {
        imageId: "img_photo123",
        transferId: "trf_photo123",
        status: "UPLOADED",
        mimeType: "image/jpeg",
        size: 3,
        sha256: "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
        bytes: new Uint8Array([1, 2, 3]),
      },
      expectedImage: {
        imageId: "img_photo123",
        transferId: "trf_photo123",
        mimeType: "image/jpeg",
        size: 3,
        width: 640,
        height: 480,
        sha256: "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
      },
    });

    expect(result).toEqual({
      imageId: "img_photo123",
      transferId: "trf_photo123",
      mimeType: "image/jpeg",
      size: 3,
      width: 640,
      height: 480,
      sha256: "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
      bytes: new Uint8Array([1, 2, 3]),
    });
  });

  test("image parser rejects checksum mismatches with MCP_IMAGE_PARSE_FAILED", () => {
    const verifier = createDownloadedImageVerifier();

    expect(() =>
      verifier.verify({
        downloadedImage: {
          imageId: "img_photo123",
          transferId: "trf_photo123",
          status: "UPLOADED",
          mimeType: "image/jpeg",
          size: 3,
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          bytes: new Uint8Array([1, 2, 3]),
        },
        expectedImage: {
          imageId: "img_photo123",
          transferId: "trf_photo123",
          mimeType: "image/jpeg",
          size: 3,
          width: 640,
          height: 480,
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
      }),
    ).toThrow(expect.objectContaining({ code: "MCP_IMAGE_PARSE_FAILED" }));
  });
});

function createCapturePhotoSubmission(
  overrides: Partial<SubmitCapturePhotoCommandResponse> = {},
): SubmitCapturePhotoCommandResponse {
  return {
    ok: true,
    requestId: "req_photo123",
    deviceId: DEFAULT_DEVICE_ID,
    action: "capture_photo",
    status: "CREATED",
    createdAt: 1_710_000_000_000,
    statusUrl: "https://relay.example.com/api/v1/commands/req_photo123",
    image: {
      imageId: "img_photo123",
      transferId: "trf_photo123",
      status: "RESERVED",
      mimeType: "image/jpeg",
      expiresAt: 1_710_000_030_000,
    },
    ...overrides,
  };
}

function createCapturePhotoCommand(
  status: CommandRecord["status"],
  overrides: Partial<CommandRecord> = {},
): CommandRecord {
  return {
    requestId: "req_photo123",
    deviceId: DEFAULT_DEVICE_ID,
    action: "capture_photo",
    status,
    createdAt: 1_710_000_000_000,
    updatedAt: 1_710_000_000_100,
    acknowledgedAt: 1_710_000_000_050,
    completedAt: null,
    cancelledAt: null,
    result: null,
    error: null,
    image: null,
    ...overrides,
  };
}

function createCommandStatusResponse(command: CommandRecord): GetCommandStatusResponse {
  return {
    ok: true,
    command,
    timestamp: command.updatedAt,
  };
}
