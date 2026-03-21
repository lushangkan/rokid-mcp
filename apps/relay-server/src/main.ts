import { Elysia } from "elysia";
import { PROTOCOL_NAME, PROTOCOL_VERSION } from "@rokid-mcp/protocol";

const port = Number(process.env.PORT ?? 3000);

const app = new Elysia()
  .get("/health", () => ({
    ok: true,
    service: "relay-server",
    protocol: PROTOCOL_NAME,
    version: PROTOCOL_VERSION
  }))
  .listen(port);

console.log(`relay-server listening on http://localhost:${app.server?.port ?? port}`);
