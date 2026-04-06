export const PROTOCOL_NAME = "rokid-relay-protocol";
export const PROTOCOL_VERSION = "1.0" as const;
export const API_VERSION = "v1" as const;

export const WS_PATH_DEVICE = "/ws/device";
export const HTTP_PATH_COMMANDS = "/api/v1/commands";
export const HTTP_PATH_COMMAND_BY_ID = "/api/v1/commands/:requestId";
export const HTTP_PATH_DEVICE_STATUS = "/api/v1/devices/:deviceId/status";
export const HTTP_PATH_IMAGE_BY_ID = "/api/v1/images/:imageId";

export const IMAGE_UPLOAD_METHOD = "PUT" as const;
export const DEFAULT_IMAGE_CONTENT_TYPE = "image/jpeg" as const;
export const ACCEPTED_IMAGE_CONTENT_TYPES = [DEFAULT_IMAGE_CONTENT_TYPE] as const;

export const DISPLAY_TEXT_REPLACEMENT_MODE = "IMMEDIATE_REPLACEMENT" as const;
export const GLOBAL_ACTIVE_COMMAND_LIMIT = 1 as const;

export const MAX_DISPLAY_TEXT_LENGTH = 500 as const;
export const MAX_DISPLAY_TEXT_DURATION_MS = 60_000 as const;
export const DEFAULT_CAPTURE_PHOTO_QUALITY = "medium" as const;
export const DEFAULT_COMMAND_TIMEOUT_MS = 90_000 as const;
export const DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000 as const;
export const DEFAULT_HEARTBEAT_TIMEOUT_MS = 15_000 as const;
export const DEFAULT_SESSION_TTL_MS = 300_000 as const;
export const DEFAULT_MAX_IMAGE_UPLOAD_SIZE_BYTES = 10 * 1024 * 1024;
