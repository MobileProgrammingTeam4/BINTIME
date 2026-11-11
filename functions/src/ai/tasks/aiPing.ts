import { z } from "zod";
import type { AiTask } from "../types";
import { text } from "./shared";

/** BT-024 연결 확인용. 로그인한 사용자만 호출할 수 있고 live 모드면 실제 Gemini 를 한 번 부른다. */
const input = z.object({ message: text(1, 100).optional() }).strict();
const output = z.object({ reply: text(1, 200) }).strict();

export type AiPingInput = z.infer<typeof input>;
export type AiPingOutput = z.infer<typeof output>;

export const aiPingTask: AiTask<AiPingInput, AiPingOutput> = {
  name: "aiPing",
  input,
  output,
  timeoutMs: 10_000,
  dailyLimit: 50,
  mock: () => ({ reply: "pong" }),
  buildRequest: async ({ message }) => ({
    systemInstruction: "연결 확인용 요청이다. reply 필드에 한국어로 짧게(한 문장) 응답한다.",
    contents: message ?? "ping",
    temperature: 0,
    maxOutputTokens: 100,
  }),
};
