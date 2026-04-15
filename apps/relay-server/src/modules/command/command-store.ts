import type {
  CommandDispatchImage,
  CommandRecord,
  CommandStatus,
  SubmitCommandRequest,
} from "@rokid-mcp/protocol";

export type StoredCommand = {
  command: CommandRecord;
  submission: SubmitCommandRequest;
  sessionId: string;
  dispatchImage: CommandDispatchImage | null;
};

const TERMINAL_STATUSES = new Set<CommandStatus>(["COMPLETED", "FAILED", "TIMEOUT", "CANCELLED"]);

function cloneStoredCommand(command: StoredCommand): StoredCommand {
  return structuredClone(command);
}

export class CommandStore {
  private readonly commandsByRequestId = new Map<string, StoredCommand>();
  private activeRequestId: string | null = null;

  get(requestId: string): StoredCommand | null {
    const record = this.commandsByRequestId.get(requestId);
    return record ? cloneStoredCommand(record) : null;
  }

  getCommand(requestId: string): CommandRecord | null {
    return this.get(requestId)?.command ?? null;
  }

  getActive(): StoredCommand | null {
    if (!this.activeRequestId) {
      return null;
    }

    return this.get(this.activeRequestId);
  }

  save(command: StoredCommand): StoredCommand {
    const next = cloneStoredCommand(command);
    const current = this.commandsByRequestId.get(next.command.requestId);
    const nextIsTerminal = TERMINAL_STATUSES.has(next.command.status);

    if (!nextIsTerminal && this.activeRequestId && this.activeRequestId !== next.command.requestId) {
      throw new Error("A different command is already active.");
    }

    this.commandsByRequestId.set(next.command.requestId, next);

    if (nextIsTerminal) {
      if (this.activeRequestId === next.command.requestId) {
        this.activeRequestId = null;
      }
    } else {
      this.activeRequestId = next.command.requestId;
    }

    if (!current && nextIsTerminal && this.activeRequestId === next.command.requestId) {
      this.activeRequestId = null;
    }

    return cloneStoredCommand(next);
  }

  update(requestId: string, updater: (current: StoredCommand) => StoredCommand): StoredCommand | null {
    const current = this.commandsByRequestId.get(requestId);
    if (!current) {
      return null;
    }

    return this.save(updater(cloneStoredCommand(current)));
  }

  clear(): void {
    this.commandsByRequestId.clear();
    this.activeRequestId = null;
  }
}
