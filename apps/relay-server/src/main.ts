import { createDefaultApp } from "./app.ts";
import { readRelayEnv } from "./config/env.ts";
import { logger } from "./lib/logger.ts";

const env = readRelayEnv();

createDefaultApp(env).listen({
  hostname: env.host,
  port: env.port
}, ({ hostname, port }) => {
  logger.info("relay-server listening", {
    hostname,
    port,
    url: `http://${hostname}:${port}`,
  });
});
