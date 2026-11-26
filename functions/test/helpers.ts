import { MemoryMatchAccess } from "../src/ai/access";
import { MemoryChatContextAccess, type MemoryChatRoom } from "../src/ai/chatContext";
import { AiError } from "../src/ai/errors";
import type { ModelCall, ModelClient, ModelResult } from "../src/ai/gemini";
import type { AiDeps } from "../src/ai/types";
import { MemoryUsageStore } from "../src/ai/usage";

export type Step = { text?: string } | AiError;

/** 순서대로 응답하는 가짜 Gemini. 받은 호출을 기록한다. */
export class FakeModelClient implements ModelClient {
  readonly calls: ModelCall[] = [];

  constructor(private readonly steps: Step[]) {}

  async generate(call: ModelCall): Promise<ModelResult> {
    this.calls.push(call);
    const step = this.steps.shift();
    if (!step) throw new Error("no more fake steps");
    if (step instanceof AiError) throw step;
    return { text: step.text, usage: { promptTokens: 10, outputTokens: 5, totalTokens: 15 } };
  }
}

export interface TestDeps extends AiDeps {
  usageStore: MemoryUsageStore;
  client: FakeModelClient;
  sleeps: number[];
}

export function makeDeps(options: {
  mode?: AiDeps["mode"];
  model?: string;
  steps?: Step[];
  matches?: Record<string, string[]>;
  /** 생략하면 matches 의 각 매칭을 같은 키의 준비된 방(멤버 = 참여자, 메시지 없음)으로 본다. */
  chatRooms?: Record<string, MemoryChatRoom | null>;
  now?: Date;
  allowedEmailSuffixes?: string[];
} = {}): TestDeps {
  const usageStore = new MemoryUsageStore();
  const client = new FakeModelClient(options.steps ?? []);
  const sleeps: number[] = [];
  return {
    mode: options.mode ?? "mock",
    model: options.model ?? "test-model",
    modelClient: () => client,
    usage: usageStore,
    matches: new MemoryMatchAccess(options.matches ?? {}),
    chatContext: new MemoryChatContextAccess(
      options.chatRooms ??
        Object.fromEntries(
          Object.entries(options.matches ?? {}).map(([id, uids]) => [id, { chatRoomId: id, participantUids: uids }]),
        ),
    ),
    now: () => options.now ?? new Date("2026-11-09T03:00:00Z"),
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    allowedEmailSuffixes: options.allowedEmailSuffixes ?? [".ac.kr", ".edu"],
    readImage: async () => { throw new Error("readImage is not configured in this test"); },
    usageStore,
    client,
    sleeps,
  };
}

export const ctx = (uid = "uid-me") => ({ uid, requestId: "req-1" });

/** reason 이 같은 AiError 로 실패하는지 */
export async function expectReason(promise: Promise<unknown>, reason: string): Promise<AiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof AiError && error.reason === reason) return error;
    throw new Error(`expected ${reason}, got ${error instanceof AiError ? error.reason : String(error)}`);
  }
  throw new Error(`expected ${reason}, but resolved`);
}
