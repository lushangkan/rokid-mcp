import { createApp } from "./app.js";
import { loadEnv } from "./config/env.js";
import { logger } from "./lib/logger.js";

const { PORT } = loadEnv();

const app = createApp().listen(PORT);

logger.info("relay-server listening", {
  url: `http://localhost:${app.server?.port ?? PORT}`
});
