import { afterEach, beforeEach, describe, expect, mock, test } from "bun:test";

import { logger } from "./logger.js";

describe("logger", () => {
  const originalError = console.error;
  const originalInfo = console.info;

  let stderrMock: ReturnType<typeof mock>;
  let stdoutMock: ReturnType<typeof mock>;

  beforeEach(() => {
    stderrMock = mock(() => undefined);
    stdoutMock = mock(() => undefined);

    console.error = stderrMock as typeof console.error;
    console.info = stdoutMock as typeof console.info;
  });

  afterEach(() => {
    console.error = originalError;
    console.info = originalInfo;
  });

  test("logger.info writes to stderr and not stdout", () => {
    logger.info("hello", { requestId: "req-1" });

    expect(stderrMock).toHaveBeenCalledTimes(1);
    expect(stderrMock).toHaveBeenCalledWith("hello", { requestId: "req-1" });
    expect(stdoutMock).not.toHaveBeenCalled();
  });

  test("logger.error writes to stderr", () => {
    logger.error("boom");

    expect(stderrMock).toHaveBeenCalledTimes(1);
    expect(stderrMock).toHaveBeenCalledWith("boom");
    expect(stdoutMock).not.toHaveBeenCalled();
  });
});
