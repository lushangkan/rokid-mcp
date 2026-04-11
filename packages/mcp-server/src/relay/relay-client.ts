import type { GetDeviceStatusParams, GetDeviceStatusResponse } from "@rokid-mcp/protocol";
import { RelayRequestError } from "../lib/errors.js";
import { validateRelayGetDeviceStatusResponse } from "./relay-response-validator.js";

export type RelayClientConfig = {
  relayBaseUrl: string;
  relayHttpAuthToken: string;
  requestTimeoutMs: number;
};

export type RelayClient = {
  getDeviceStatus(params: GetDeviceStatusParams): Promise<GetDeviceStatusResponse>;
};

export function createRelayClient(config: RelayClientConfig): RelayClient {
  return {
    async getDeviceStatus(params) {
      const controller = new AbortController();
      const timer = setTimeout(() => {
        controller.abort();
      }, config.requestTimeoutMs);

      let response: Response;
      try {
        response = await fetch(`${config.relayBaseUrl}/api/v1/devices/${encodeURIComponent(params.deviceId)}/status`, {
          method: "GET",
          headers: {
            Authorization: `Bearer ${config.relayHttpAuthToken}`,
          },
          signal: controller.signal,
        });
      } catch (error) {
        clearTimeout(timer);
        throw new RelayRequestError(
          "MCP_RELAY_REQUEST_FAILED",
          "Failed to request relay get device status",
          true,
          {
            cause: error instanceof Error ? error.message : String(error),
          },
        );
      }

      clearTimeout(timer);

      let payload: unknown;
      try {
        payload = await response.json();
      } catch (error) {
        throw new RelayRequestError(
          "MCP_RELAY_REQUEST_FAILED",
          "Failed to parse relay response JSON",
          false,
          {
            cause: error instanceof Error ? error.message : String(error),
          },
        );
      }

      const validation = validateRelayGetDeviceStatusResponse(payload);
      if (!validation.ok) {
        throw new RelayRequestError(
          validation.error.code,
          validation.error.message,
          validation.error.retryable,
          validation.error.details,
        );
      }

      return validation.value;
    },
  };
}
