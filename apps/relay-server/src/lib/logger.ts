export type LogContext = Record<string, unknown>;

export interface Logger {
  info: (message: string, context?: LogContext) => void;
  error: (message: string, context?: LogContext) => void;
}

function writeLog(
  method: "info" | "error",
  message: string,
  context?: LogContext,
): void {
  if (context && Object.keys(context).length > 0) {
    console[method](message, context);
    return;
  }

  console[method](message);
}

export function toLogError(error: unknown): unknown {
  if (error instanceof Error) {
    return {
      name: error.name,
      message: error.message,
      stack: error.stack,
    };
  }

  return error;
}

export const consoleLogger: Logger = {
  info: (message: string, context?: LogContext) => {
    writeLog("info", message, context);
  },
  error: (message: string, context?: LogContext) => {
    writeLog("error", message, context);
  },
};

export const logger = consoleLogger;
