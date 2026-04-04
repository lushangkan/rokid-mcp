import { createDefaultApp } from "./app.js";
import { readRelayEnv } from "./config/env.js";

const env = readRelayEnv();

createDefaultApp(env).listen({
  hostname: env.host,
  port: env.port
}, ({ hostname, port }) => {
  console.log(`relay-server listening on http://${hostname}:${port}`);
});
