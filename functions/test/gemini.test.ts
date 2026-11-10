import { ApiError } from "@google/genai";
import { logger } from "firebase-functions";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AiError, toHttpsError } from "../src/ai/errors";
import { runAiTask } from "../src/ai/gateway";
import { classify } from "../src/ai/gemini";
import { aiPingTask } from "../src/ai/tasks/aiPing";
import { ctx, expectReason, makeDeps } from "./helpers";

const api = (status: number) => new ApiError({ status, message: `upstream body with secret AQ.leak ${status}` });

describe("Gemini 오류 분류", () => {
  it.each([
    [429, "AI_RATE_LIMITED", true, true],
    [500, "AI_UNAVAILABLE", true, true],
    [503, "AI_UNAVAILABLE", true, true],
    [400, "AI_UNAVAILABLE", false, false],
    [401, "AI_UNAVAILABLE", false, false],
    [402, "AI_UNAVAILABLE", false, false],
    [403, "AI_UNAVAILABLE", false, false],
    [404, "AI_UNAVAILABLE", false, false],
  ])("HTTP %i → %s (게이트웨이 재시도 %s, 앱 retryable %s)", (status, reason, transient, retryable) => {
    const error = classify(api(status), false);
    expect(error).toMatchObject({ reason, transient, retryable, upstreamStatus: status });
    expect(error.message).not.toContain("AQ.");
    expect(toHttpsError(error, "r").details).toMatchObject({ reason, retryable });
  });

  it("앱으로 가는 details 에는 상태 코드가 없다", () => {
    expect(toHttpsError(classify(api(402), false), "r").details).not.toHaveProperty("upstreamStatus");
  });

  it("중단·시간 초과는 AI_TIMEOUT", () => {
    expect(classify(new Error("x"), true).reason).toBe("AI_TIMEOUT");
  });
});

describe("실패 로그", () => {
  afterEach(() => vi.restoreAllMocks());

  it("402 는 재시도 없이 실패하고 ai_call 로그에 상태 코드만 남긴다", async () => {
    const info = vi.spyOn(logger, "info").mockImplementation(() => undefined);
    const deps = makeDeps({ mode: "live", steps: [classify(api(402), false), { text: '{"reply":"never"}' }] });
    const error = await expectReason(runAiTask(aiPingTask, {}, ctx(), deps), "AI_UNAVAILABLE");
    expect(error.retryable).toBe(false);
    expect(deps.client.calls).toHaveLength(1);
    const entry = info.mock.calls.find(([message]) => message === "ai_call")?.[1] as Record<string, unknown>;
    expect(entry).toMatchObject({ outcome: "AI_UNAVAILABLE", upstreamStatus: 402, attempts: 1 });
    expect(JSON.stringify(entry)).not.toContain("AQ.");
  });

  it("성공하면 upstreamStatus 를 남기지 않는다", async () => {
    const info = vi.spyOn(logger, "info").mockImplementation(() => undefined);
    await runAiTask(aiPingTask, {}, ctx(), makeDeps());
    const entry = info.mock.calls.find(([message]) => message === "ai_call")?.[1] as Record<string, unknown>;
    expect(entry.upstreamStatus).toBeUndefined();
  });

  it("사유 기본 retryable 을 쓰는 오류는 그대로", () => {
    expect(new AiError("AI_TIMEOUT").retryable).toBe(true);
    expect(new AiError("QUOTA_EXCEEDED").retryable).toBe(false);
  });
});
