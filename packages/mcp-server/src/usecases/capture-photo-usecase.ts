import type {
  CapturePhotoCommandResult,
  CapturePhotoQuality,
  CommandImage,
  CommandRecord,
  DeviceId,
  SubmitCapturePhotoCommandResponse,
  SubmitCommandResponse,
} from "@rokid-mcp/protocol";

import type { CommandPoller } from "../command/command-poller.js";
import type { ImageDownloader } from "../image/image-downloader.js";
import type { DownloadedImageVerifier, ExpectedCapturePhotoImage } from "../image/image-verifier.js";
import { RelayRequestError } from "../lib/errors.js";
import { logger } from "../lib/logger.js";
import type { CapturePhotoResultMapper } from "../mapper/capture-photo-result-mapper.js";
import type { McpToolResult } from "../mapper/mcp-result-mapper.js";
import type { RelayCommandClient } from "../relay/relay-command-client.js";

const DEFAULT_CAPTURE_PHOTO_QUALITY: CapturePhotoQuality = "medium";

export type CapturePhotoUseCaseInput = {
  deviceId: DeviceId;
  quality?: CapturePhotoQuality;
};

export type CapturePhotoUseCase = {
  execute(input: CapturePhotoUseCaseInput): Promise<McpToolResult>;
};

type CompletedCapturePhotoCommand = {
  requestId: string;
  completedAt: number;
  result: CapturePhotoCommandResult;
  image: CommandImage;
};

export function createCapturePhotoUseCase(deps: {
  relayCommandClient: Pick<RelayCommandClient, "submitCommand">;
  commandPoller: CommandPoller;
  imageDownloader: ImageDownloader;
  downloadedImageVerifier: DownloadedImageVerifier;
  resultMapper: CapturePhotoResultMapper;
  encodeBase64?: (bytes: Uint8Array) => string;
}): CapturePhotoUseCase {
  const encodeBase64 = deps.encodeBase64 ?? defaultEncodeBase64;

  return {
    async execute(input) {
      const quality = input.quality ?? DEFAULT_CAPTURE_PHOTO_QUALITY;
      logger.info("capture-photo workflow started", {
        toolName: "rokid.capture_photo",
        deviceId: input.deviceId,
        quality,
      });

      const submission = assertCapturePhotoSubmission(
        await deps.relayCommandClient.submitCommand({
          deviceId: input.deviceId,
          action: "capture_photo",
          payload: { quality },
        }),
      );

      logger.info("capture-photo command submitted", {
        toolName: "rokid.capture_photo",
        deviceId: input.deviceId,
        requestId: submission.requestId,
        imageId: submission.image.imageId,
      });

      const command = await deps.commandPoller.pollUntilTerminal(submission.requestId);

      logger.info("capture-photo polling finished", {
        toolName: "rokid.capture_photo",
        requestId: command.requestId,
        status: command.status,
      });

      if (command.status !== "COMPLETED") {
        return deps.resultMapper.toTerminalFailureResult(command);
      }

      const completedCommand = validateCompletedCapturePhotoCommand(command, submission);
      const expectedImage = buildExpectedImage(completedCommand);
      const downloadedImage = await deps.imageDownloader.download({
        imageId: expectedImage.imageId,
        expectedSize: expectedImage.size,
        expectedMimeType: expectedImage.mimeType,
      });
      const verifiedImage = deps.downloadedImageVerifier.verify({
        downloadedImage,
        expectedImage,
      });
      const dataBase64 = encodeBase64(verifiedImage.bytes);

      if (dataBase64.length === 0) {
        throw new RelayRequestError("MCP_IMAGE_PARSE_FAILED", "Failed to encode the downloaded image as base64.", false, {
          requestId: completedCommand.requestId,
          imageId: verifiedImage.imageId,
        });
      }

      return deps.resultMapper.toSuccessResult({
        requestId: completedCommand.requestId,
        completedAt: completedCommand.completedAt,
        image: verifiedImage,
        dataBase64,
      });
    },
  };
}

function assertCapturePhotoSubmission(response: SubmitCommandResponse): SubmitCapturePhotoCommandResponse {
  if (response.action !== "capture_photo") {
    throw createRelayResponseInvalidError("Relay accepted capture_photo but returned a non-photo submission response.");
  }

  return response;
}

function validateCompletedCapturePhotoCommand(
  command: CommandRecord,
  submission: SubmitCapturePhotoCommandResponse,
): CompletedCapturePhotoCommand {
  if (command.action !== "capture_photo") {
    throw createRelayResponseInvalidError("Relay completed a non-photo command for the capture photo workflow.", {
      requestId: command.requestId,
      action: command.action,
    });
  }

  if (command.status !== "COMPLETED") {
    throw createRelayResponseInvalidError("Relay capture photo command was not completed.", {
      requestId: command.requestId,
      status: command.status,
    });
  }

  if (command.requestId !== submission.requestId) {
    throw createRelayResponseInvalidError("Relay capture photo command request ID did not match the submitted request.", {
      requestId: command.requestId,
      submittedRequestId: submission.requestId,
    });
  }

  if (command.completedAt === null) {
    throw createRelayResponseInvalidError("Relay completed the capture photo command without a completedAt timestamp.", {
      requestId: command.requestId,
    });
  }

  if (command.error !== null) {
    throw createRelayResponseInvalidError("Relay completed the capture photo command with an unexpected terminal error payload.", {
      requestId: command.requestId,
      errorCode: command.error.code,
    });
  }

  if (!isCapturePhotoCommandResult(command.result)) {
    throw createRelayResponseInvalidError("Relay completed the capture photo command without a usable image result.", {
      requestId: command.requestId,
    });
  }

  if (command.image === null) {
    throw createRelayResponseInvalidError("Relay completed the capture photo command without image metadata.", {
      requestId: command.requestId,
    });
  }

  if (command.image.status !== "UPLOADED") {
    throw createRelayResponseInvalidError("Relay completed the capture photo command before the image reached UPLOADED.", {
      requestId: command.requestId,
      imageId: command.image.imageId,
      imageStatus: command.image.status,
    });
  }

  assertSubmissionMetadataMatches(submission, command.result, command.image);

  return {
    requestId: command.requestId,
    completedAt: command.completedAt,
    result: command.result,
    image: command.image,
  };
}

function assertSubmissionMetadataMatches(
  submission: SubmitCapturePhotoCommandResponse,
  result: CapturePhotoCommandResult,
  image: CommandImage,
): void {
  if (result.imageId !== submission.image.imageId || image.imageId !== submission.image.imageId) {
    throw createRelayResponseInvalidError("Relay capture photo image ID drifted between submission and completion.", {
      submittedImageId: submission.image.imageId,
      resultImageId: result.imageId,
      commandImageId: image.imageId,
    });
  }

  if (result.transferId !== submission.image.transferId || image.transferId !== submission.image.transferId) {
    throw createRelayResponseInvalidError("Relay capture photo transfer ID drifted between submission and completion.", {
      submittedTransferId: submission.image.transferId,
      resultTransferId: result.transferId,
      commandImageTransferId: image.transferId,
    });
  }

  if (result.mimeType !== submission.image.mimeType || image.mimeType !== submission.image.mimeType) {
    throw createRelayResponseInvalidError("Relay capture photo MIME type drifted between submission and completion.", {
      submittedMimeType: submission.image.mimeType,
      resultMimeType: result.mimeType,
      commandImageMimeType: image.mimeType,
    });
  }

  if (image.size !== undefined && image.size !== result.size) {
    throw createRelayResponseInvalidError("Relay command image size did not match the capture photo result.", {
      resultSize: result.size,
      imageSize: image.size,
    });
  }

  if (image.width !== undefined && image.width !== result.width) {
    throw createRelayResponseInvalidError("Relay command image width did not match the capture photo result.", {
      resultWidth: result.width,
      imageWidth: image.width,
    });
  }

  if (image.height !== undefined && image.height !== result.height) {
    throw createRelayResponseInvalidError("Relay command image height did not match the capture photo result.", {
      resultHeight: result.height,
      imageHeight: image.height,
    });
  }

  if (result.sha256 && image.sha256 && result.sha256 !== image.sha256) {
    throw createRelayResponseInvalidError("Relay command image checksum did not match the capture photo result.", {
      resultSha256: result.sha256,
      imageSha256: image.sha256,
    });
  }
}

function buildExpectedImage(command: CompletedCapturePhotoCommand): ExpectedCapturePhotoImage {
  return {
    imageId: command.result.imageId,
    transferId: command.result.transferId,
    mimeType: command.result.mimeType,
    size: command.result.size,
    width: command.result.width,
    height: command.result.height,
    ...(command.result.sha256 ?? command.image.sha256 ? { sha256: command.result.sha256 ?? command.image.sha256 } : {}),
  };
}

function isCapturePhotoCommandResult(result: CommandRecord["result"]): result is CapturePhotoCommandResult {
  return result?.action === "capture_photo";
}

function createRelayResponseInvalidError(message: string, details: Record<string, unknown> = {}): RelayRequestError {
  return new RelayRequestError("MCP_RELAY_RESPONSE_INVALID", message, false, details);
}

function defaultEncodeBase64(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64");
}
