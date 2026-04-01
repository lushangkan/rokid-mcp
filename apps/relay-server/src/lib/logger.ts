export interface Logger {
  info: (message: string, payload?: unknown) => void;
  error: (message: string, payload?: unknown) => void;
}

export const consoleLogger: Logger = {
  info: (message: string, payload?: unknown) => {
    console.info(message, payload);
  },
  error: (message: string, payload?: unknown) => {
    console.error(message, payload);
  }
};
