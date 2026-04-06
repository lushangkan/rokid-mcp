import {
  DEFAULT_COMMAND_TIMEOUT_MS,
  DEFAULT_HEARTBEAT_TIMEOUT_MS,
  DEFAULT_IMAGE_CONTENT_TYPE,
  DEFAULT_MAX_IMAGE_UPLOAD_SIZE_BYTES,
  type ImageContentType,
  GLOBAL_ACTIVE_COMMAND_LIMIT,
} from "@rokid-mcp/protocol";

export type CommandConfig = {
  activeCommandLimit: typeof GLOBAL_ACTIVE_COMMAND_LIMIT;
  ackTimeoutMs: number;
  executionTimeoutMs: number;
  imageReservationTtlMs: number;
  imageContentType: ImageContentType;
  maxImageUploadSizeBytes: number;
};

const DEFAULT_ACK_TIMEOUT_MS = DEFAULT_HEARTBEAT_TIMEOUT_MS;

export const DEFAULT_COMMAND_CONFIG: CommandConfig = {
  activeCommandLimit: GLOBAL_ACTIVE_COMMAND_LIMIT,
  ackTimeoutMs: DEFAULT_ACK_TIMEOUT_MS,
  executionTimeoutMs: DEFAULT_COMMAND_TIMEOUT_MS,
  imageReservationTtlMs: DEFAULT_COMMAND_TIMEOUT_MS,
  imageContentType: DEFAULT_IMAGE_CONTENT_TYPE,
  maxImageUploadSizeBytes: DEFAULT_MAX_IMAGE_UPLOAD_SIZE_BYTES,
};

export function createCommandConfig(overrides: Partial<CommandConfig> = {}): CommandConfig {
  return {
    ...DEFAULT_COMMAND_CONFIG,
    ...overrides,
    activeCommandLimit: GLOBAL_ACTIVE_COMMAND_LIMIT,
    imageContentType: DEFAULT_IMAGE_CONTENT_TYPE,
  };
}
