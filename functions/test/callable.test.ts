import { HttpsError } from "firebase-functions/v2/https";
import { describe, expect, it } from "vitest";
import { createAiCallable } from "../src/ai/callable";
import { AiError, REASONS, toHttpsError } from "../src/ai/errors";
import { aiPingTask } from "../src/ai/tasks/aiPing";
import { makeDeps } from "./helpers";

const request = (auth?: { uid: string }) =>
  ({ data: {}, auth: auth ? { uid: auth.uid, token: {} } : undefined, rawRequest: {} }) as never;

describe("Callable 래퍼", () => {
  it("Auth 토큰이 없으면 unauthenticated + 표준 details", async () => {
    const fn = createAiCallable(aiPingTask, {}, () => makeDeps());
    await expect(fn.run(request())).rejects.toMatchObject({
      code: "unauthenticated",
      details: { reason: "UNAUTHENTICATED", retryable: false, requestId: expect.any(String) },
    });
  });

  it("Auth 토큰의 uid 로 실행한다", async () => {
    const deps = makeDeps();
    const fn = createAiCallable(aiPingTask, {}, () => deps);
    const res = await fn.run(request({ uid: "uid-me" }));
    expect(res.data).toEqual({ reply: "pong" });
    expect([...deps.usageStore.counts.keys()][0]).toMatch(/^uid-me_/);
  });
});

describe("toHttpsError", () => {
  it("모든 사유가 HttpsError 코드로 바뀐다", () => {
    for (const reason of Object.keys(REASONS) as Array<keyof typeof REASONS>) {
      const error = toHttpsError(new AiError(reason, "secret internal detail"), "r1");
      expect(error).toBeInstanceOf(HttpsError);
      expect(error.code).toBe(REASONS[reason].code);
      expect(error.message).not.toContain("secret internal detail");
      expect(error.details).toMatchObject({ reason, retryable: REASONS[reason].retryable, requestId: "r1" });
    }
  });

  it("예상하지 못한 오류는 원문을 숨기고 INTERNAL", () => {
    const error = toHttpsError(new Error("stack trace with key AIza..."), "r2");
    expect(error.code).toBe("internal");
    expect(error.message).not.toContain("AIza");
    expect(error.details).toMatchObject({ reason: "INTERNAL" });
  });
});
