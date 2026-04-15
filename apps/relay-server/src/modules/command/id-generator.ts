export type CommandIdGenerator = {
  createRequestId(): string;
  createImageId(): string;
  createTransferId(): string;
  createUploadToken(): string;
};

type DefaultCommandIdGeneratorOptions = {
  randomUUID?: () => string;
};

function formatId(prefix: string, randomUUID: () => string): string {
  return `${prefix}_${randomUUID().replace(/-/g, "_")}`;
}

export class DefaultCommandIdGenerator implements CommandIdGenerator {
  private readonly randomUUID: () => string;

  constructor(options: DefaultCommandIdGeneratorOptions = {}) {
    this.randomUUID = options.randomUUID ?? (() => crypto.randomUUID());
  }

  createRequestId(): string {
    return formatId("req", this.randomUUID);
  }

  createImageId(): string {
    return formatId("img", this.randomUUID);
  }

  createTransferId(): string {
    return formatId("trf", this.randomUUID);
  }

  createUploadToken(): string {
    return formatId("upl", this.randomUUID);
  }
}
