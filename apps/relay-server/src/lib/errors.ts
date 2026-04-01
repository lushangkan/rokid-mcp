export class RelayAppError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly retryable = false
  ) {
    super(message);
    this.name = "RelayAppError";
  }
}
