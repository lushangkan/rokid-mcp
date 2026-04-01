import { nowIsoString } from "./clock.js";

type Level = "INFO" | "ERROR";

function log(level: Level, message: string, context?: unknown): void {
  const payload = {
    ts: nowIsoString(),
    level,
    msg: message,
    ...(context === undefined ? {} : { context })
  };

  const line = JSON.stringify(payload);
  if (level === "ERROR") {
    console.error(line);
    return;
  }

  console.log(line);
}

export const logger = {
  info(message: string, context?: unknown): void {
    log("INFO", message, context);
  },
  error(message: string, context?: unknown): void {
    log("ERROR", message, context);
  }
};
