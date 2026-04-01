export class AppError extends Error {
  readonly status: number;

  constructor(message: string, status = 500) {
    super(message);
    this.name = "AppError";
    this.status = status;
  }
}

export function toErrorResponse(error: unknown): Response {
  if (error instanceof AppError) {
    return Response.json({ ok: false, error: error.message }, { status: error.status });
  }

  return Response.json({ ok: false, error: "Internal Server Error" }, { status: 500 });
}
