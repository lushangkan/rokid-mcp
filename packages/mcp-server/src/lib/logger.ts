type LogContext = Record<string, unknown>;

export const logger = {
  info(message: string, context?: LogContext): void {
    if (context) {
      console.info(message, context);
      return;
    }

    console.info(message);
  },
  error(message: string, context?: LogContext): void {
    if (context) {
      console.error(message, context);
      return;
    }

    console.error(message);
  },
};
