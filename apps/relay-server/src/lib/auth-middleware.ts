import type { ErrorResponse } from "@rokid-mcp/protocol";
import { Elysia } from "elysia";

import type { RelayEnv } from "../config/env.ts";
import { extractBearerToken, validateTokenAgainstAllowlist } from "./auth.ts";

type HttpAuthPolicy = "public" | "capability-token-only" | "bearer";
type HttpMethod = "GET" | "POST" | "PUT";

type HttpAuthRouteDefinition = {
  method: HttpMethod;
  path: string;
  policy: HttpAuthPolicy;
};

const RELAY_HTTP_AUTH_ROUTE_MATRIX: readonly HttpAuthRouteDefinition[] = [
  { method: "GET", path: "/health", policy: "public" },
  { method: "PUT", path: "/api/v1/images/:imageId", policy: "capability-token-only" },
  { method: "GET", path: "/api/v1/devices/:deviceId/status", policy: "bearer" },
  { method: "POST", path: "/api/v1/commands", policy: "bearer" },
  { method: "GET", path: "/api/v1/commands/:requestId", policy: "bearer" },
  { method: "GET", path: "/api/v1/images/:imageId", policy: "bearer" },
];

const RELAY_HTTP_AUTH_MATCHERS = RELAY_HTTP_AUTH_ROUTE_MATRIX.map((route) => ({
  ...route,
  pattern: createPathPattern(route.path),
}));

const RELAY_DEVICE_WS_PATH = "/ws/device";

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function createPathPattern(path: string): RegExp {
  const pattern = path
    .split("/")
    .map((segment) => (segment.startsWith(":") ? "[^/]+" : escapeRegExp(segment)))
    .join("/");

  return new RegExp(`^${pattern}$`);
}

function resolveHttpAuthPolicy(request: Request): HttpAuthPolicy {
  const pathname = new URL(request.url).pathname;
  const method = request.method.toUpperCase();

  const matchedRoute = RELAY_HTTP_AUTH_MATCHERS.find(
    (route) => route.method === method && route.pattern.test(pathname),
  );

  // Fail-closed: if route not explicitly marked as public or capability-token-only,
  // require bearer authentication
  return matchedRoute?.policy ?? "bearer";
}

export function isRelayDeviceWebSocketUpgradeRequest(request: Request): boolean {
  const pathname = new URL(request.url).pathname;
  const upgradeHeader = request.headers.get("upgrade")?.toLowerCase();

  return pathname === RELAY_DEVICE_WS_PATH && upgradeHeader === "websocket";
}

function createUnauthorizedResponse(): ErrorResponse {
  return {
    ok: false,
    error: {
      code: "AUTH_HTTP_BEARER_INVALID",
      message: "Authorization header must contain a valid bearer token.",
      retryable: false,
    },
    timestamp: Date.now(),
  };
}

export function createRelayHttpAuthMiddleware(options: Pick<RelayEnv, "httpAuthTokens">) {
  return new Elysia({ name: "relay-http-auth-middleware" }).onBeforeHandle(
    { as: "global" },
    ({ request, set }) => {
      if (isRelayDeviceWebSocketUpgradeRequest(request)) {
        return;
      }

      const policy = resolveHttpAuthPolicy(request);

      if (policy !== "bearer") {
        return;
      }

      const authHeader = request.headers.get("authorization") ?? "";
      const token = extractBearerToken(authHeader);

      if (!token || !validateTokenAgainstAllowlist(token, options.httpAuthTokens)) {
        set.status = 401;
        return createUnauthorizedResponse();
      }
    },
  );
}
