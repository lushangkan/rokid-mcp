import { createDefaultApp } from "./app.ts";
import { readRelayEnv } from "./config/env.ts";

const env = readRelayEnv();

createDefaultApp(env).listen({
  hostname: env.host,
  port: env.port
}, ({ hostname, port }) => {
  console.log(`relay-server listening on http://${hostname}:${port}`);
});
