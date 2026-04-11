import { describe, expect, test } from "bun:test";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";

import { startStdioServer } from "./stdio.js";

describe("startStdioServer", () => {
  test("connects the created server to the created transport", async () => {
    const calls: Array<{ kind: "server" | "connect" | "transport"; value?: unknown }> = [];
    const transport: Transport = {
      start: async () => undefined,
      send: async () => undefined,
      close: async () => undefined,
    };

    await startStdioServer({
      env: {
        relayBaseUrl: "http://localhost:3000",
        relayHttpAuthToken: "relay-http-token",
        requestTimeoutMs: 5_000,
        defaultDeviceId: "rokid_glasses_01",
        commandPollIntervalMs: 250,
        commandTimeoutMs: 30_000,
      },
      createServer: (deps) => {
        calls.push({ kind: "server", value: deps });

        return {
          connect: async (receivedTransport) => {
            calls.push({ kind: "connect", value: receivedTransport });
          },
        };
      },
      createTransport: () => {
        calls.push({ kind: "transport" });
        return transport;
      },
    });

    expect(calls).toEqual([
      {
        kind: "server",
        value: {
          env: {
            relayBaseUrl: "http://localhost:3000",
            relayHttpAuthToken: "relay-http-token",
            requestTimeoutMs: 5_000,
            defaultDeviceId: "rokid_glasses_01",
            commandPollIntervalMs: 250,
            commandTimeoutMs: 30_000,
          },
        },
      },
      { kind: "transport" },
      { kind: "connect", value: transport },
    ]);
  });
});
