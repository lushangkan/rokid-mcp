import { Type } from "@sinclair/typebox";
import { Value } from "@sinclair/typebox/value";

const EnvSchema = Type.Object({
  PORT: Type.Optional(Type.String({ pattern: "^[0-9]+$" }))
});

export type Env = {
  PORT: number;
};

export function loadEnv(source: Record<string, string | undefined> = process.env): Env {
  const input = { PORT: source.PORT };

  if (!Value.Check(EnvSchema, input)) {
    throw new Error("Invalid environment variables");
  }

  return {
    PORT: Number(input.PORT ?? "3000")
  };
}
