import { afterEach, describe, expect, it, vi } from "vitest";
import { logger } from "firebase-functions";
import { classify, type ModelCall, type ModelClient } from "../src/ai/gemini";
import { ApiError } from "@google/genai";
import { resolveMode, seoulDateKey } from "../src/ai/config";
import { AiError } from "../src/ai/errors";
import { runAiTask, toGeminiSchema } from "../src/ai/gateway";
import { aiPingTask } from "../src/ai/tasks/aiPing";
import { generateProfileTask } from "../src/ai/tasks/generateProfile";
import { suggestChatRepliesTask } from "../src/ai/tasks/suggestChatReplies";
import { ctx, expectReason, makeDeps } from "./helpers";

describe("runAiTask 공통 처리", () => {
  it("uid 가 없으면 UNAUTHENTICATED", async () => {
    await expectReason(runAiTask(aiPingTask, {}, { uid: "", requestId: "r" }, makeDeps()), "UNAUTHENTICATED");
  });

  it("mock 모드는 Gemini 를 부르지 않고 표준 형식으로 응답한다", async () => {
    const deps = makeDeps();
    const res = await runAiTask(aiPingTask, undefined, ctx(), deps);
    expect(res).toMatchObject({ task: "aiPing", data: { reply: "pong" }, meta: { model: "mock", requestId: "req-1", mock: true } });
    expect(deps.client.calls).toHaveLength(0);
  });

  it("입력 스키마 위반은 INVALID_INPUT 이고 필드 경로만 알려준다", async () => {
    const error = await expectReason(
      runAiTask(aiPingTask, { message: "x".repeat(101), extra: 1 }, ctx(), makeDeps()),
      "INVALID_INPUT",
    );
    expect(error.fields).toEqual(expect.arrayContaining(["message"]));
    expect(JSON.stringify(error.fields)).not.toContain("xxx");
  });

  it("하루 한도를 넘으면 QUOTA_EXCEEDED, 날짜가 바뀌면 다시 허용", async () => {
    const deps = makeDeps();
    for (let i = 0; i < aiPingTask.dailyLimit; i++) await runAiTask(aiPingTask, {}, ctx(), deps);
    await expectReason(runAiTask(aiPingTask, {}, ctx(), deps), "QUOTA_EXCEEDED");
    // 다른 사용자는 영향 없음
    await runAiTask(aiPingTask, {}, ctx("uid-other"), deps);
    // 서울 기준 다음 날(2026-11-10 00:00 KST = 11-09 15:00Z)
    const nextDay = { ...deps, now: () => new Date("2026-11-09T15:00:00Z") };
    await runAiTask(aiPingTask, {}, ctx(), nextDay);
  });

  it("권한 검사가 호출량 차감보다 먼저다", async () => {
    const deps = makeDeps({ matches: { m1: ["a", "b"] } });
    await expectReason(runAiTask(suggestChatRepliesTask, { matchId: "m1" }, ctx("intruder"), deps), "PERMISSION_DENIED");
    expect(deps.usageStore.counts.size).toBe(0);
  });
});

describe("live 모드", () => {
  it("응답 JSON 을 검증해 돌려주고 스키마를 Gemini 에 보낸다", async () => {
    const deps = makeDeps({ mode: "live", steps: [{ text: '{"reply":"연결됐어요"}' }] });
    const res = await runAiTask(aiPingTask, { message: "hi" }, ctx(), deps);
    expect(res.data.reply).toBe("연결됐어요");
    expect(res.meta).toMatchObject({ model: "test-model", mock: false });
    const call = deps.client.calls[0];
    expect(call.model).toBe("test-model");
    expect(call.timeoutMs).toBe(aiPingTask.timeoutMs);
    expect(call.jsonSchema).toMatchObject({ type: "object", required: ["reply"] });
  });

  it("JSON 이 아니면 한 번 다시 요청하고 성공하면 그 결과를 쓴다", async () => {
    const deps = makeDeps({ mode: "live", steps: [{ text: "pong!" }, { text: '{"reply":"pong"}' }] });
    const res = await runAiTask(aiPingTask, {}, ctx(), deps);
    expect(res.data.reply).toBe("pong");
    expect(deps.client.calls).toHaveLength(2);
    expect(deps.sleeps).toEqual([500]);
  });

  it("두 번 모두 형식 오류면 AI_INVALID_OUTPUT", async () => {
    const deps = makeDeps({ mode: "live", steps: [{ text: '{"reply":""}' }, { text: '{"wrong":1}' }] });
    await expectReason(runAiTask(aiPingTask, {}, ctx(), deps), "AI_INVALID_OUTPUT");
    expect(deps.client.calls).toHaveLength(2);
  });

  it("429 는 한 번 재시도한다", async () => {
    const deps = makeDeps({
      mode: "live",
      steps: [new AiError("AI_RATE_LIMITED", "429", { transient: true }), { text: '{"reply":"ok"}' }],
    });
    expect((await runAiTask(aiPingTask, {}, ctx(), deps)).data.reply).toBe("ok");
  });

  it("시간 초과·차단은 재시도하지 않는다", async () => {
    const timeout = makeDeps({ mode: "live", steps: [new AiError("AI_TIMEOUT"), { text: '{"reply":"late"}' }] });
    await expectReason(runAiTask(aiPingTask, {}, ctx(), timeout), "AI_TIMEOUT");
    expect(timeout.client.calls).toHaveLength(1);

    const blocked = makeDeps({ mode: "live", steps: [new AiError("AI_BLOCKED")] });
    await expectReason(runAiTask(aiPingTask, {}, ctx(), blocked), "AI_BLOCKED");
  });

  it("프롬프트가 아직 없는 작업은 AI_NOT_IMPLEMENTED", async () => {
    const deps = makeDeps({ mode: "live" });
    // BT-029 에서 generateProfile 프롬프트가 생겨, 프롬프트(buildRequest)를 뺀 사본으로 검사한다.
    const withoutPrompt = { ...generateProfileTask, buildRequest: undefined };
    await expectReason(
      runAiTask(withoutPrompt, { department: "소프트웨어학과", grade: 3 }, ctx(), deps),
      "AI_NOT_IMPLEMENTED",
    );
  });

  it("모델 ID 가 없으면 AI_UNAVAILABLE", async () => {
    const deps = makeDeps({ mode: "live", model: "" });
    await expectReason(runAiTask(aiPingTask, {}, ctx(), deps), "AI_UNAVAILABLE");
    expect(deps.client.calls).toHaveLength(0);
  });
});

describe("설정", () => {
  it("AI_MODE 가 없으면 에뮬레이터는 mock, 배포는 live", () => {
    expect(resolveMode({ FUNCTIONS_EMULATOR: "true" })).toBe("mock");
    expect(resolveMode({})).toBe("live");
    expect(resolveMode({ AI_MODE: "live", FUNCTIONS_EMULATOR: "true" })).toBe("live");
    expect(resolveMode({ AI_MODE: " MOCK " })).toBe("mock");
  });

  it("호출량 날짜는 Asia/Seoul 기준", () => {
    expect(seoulDateKey(new Date("2026-11-09T14:59:59Z"))).toBe("2026-11-09");
    expect(seoulDateKey(new Date("2026-11-09T15:00:00Z"))).toBe("2026-11-10");
  });

  it("Gemini 로 보내는 스키마에는 $schema 가 없다", () => {
    expect(toGeminiSchema(aiPingTask.output)).not.toHaveProperty("$schema");
  });
});

describe("제한 시간은 재시도까지 포함한 전체 시간(#48)", () => {
  afterEach(() => vi.restoreAllMocks());

  /** 각 시도가 elapsedMs 만큼 시계를 움직인 뒤 결과를 내는 가짜 Gemini. 대기(sleep)도 시계를 움직인다. */
  function timedDeps(steps: { elapsedMs: number; result: AiError | { text: string } }[]) {
    let clock = 1_000_000;
    const calls: ModelCall[] = [];
    const client: ModelClient = {
      async generate(call) {
        calls.push(call);
        const step = steps.shift();
        if (!step) throw new Error("no more steps");
        clock += step.elapsedMs;
        if (step.result instanceof AiError) throw step.result;
        return { text: step.result.text };
      },
    };
    const deps = makeDeps({ mode: "live" });
    return Object.assign(deps, {
      modelClient: () => client,
      clockMs: () => clock,
      sleep: async (ms: number) => { deps.sleeps.push(ms); clock += ms; },
      calls,
    });
  }
  const unavailable = () => new AiError("AI_UNAVAILABLE", "503", { transient: true, upstreamStatus: 503 });
  const budget = aiPingTask.timeoutMs; // 10초

  it("마감 초과 504 는 재시도하지 않아 최악 시간이 timeoutMs 를 넘지 않는다", async () => {
    const deadline504 = classify(new ApiError({ status: 504, message: '{"error":{"status":"DEADLINE_EXCEEDED"}}' }), false);
    const deps = timedDeps([{ elapsedMs: budget - 300, result: deadline504 }, { elapsedMs: 100, result: { text: '{"reply":"late"}' } }]);
    await expectReason(runAiTask(aiPingTask, {}, ctx(), deps), "AI_TIMEOUT");
    expect(deps.calls).toHaveLength(1);
    expect(deps.sleeps).toEqual([]);
  });

  it("일찍 돌아온 503 은 재시도하고, 두 번째 시도는 남은 시간만 쓴다", async () => {
    const deps = timedDeps([{ elapsedMs: 2_000, result: unavailable() }, { elapsedMs: 1_000, result: { text: '{"reply":"ok"}' } }]);
    expect((await runAiTask(aiPingTask, {}, ctx(), deps)).data.reply).toBe("ok");
    expect(deps.calls.map((call) => call.timeoutMs)).toEqual([budget, budget - 2_000 - 500]);
  });

  it("남은 시간이 제한 시간의 절반보다 적으면 재시도하지 않고 첫 오류를 돌려준다", async () => {
    const info = vi.spyOn(logger, "info").mockImplementation(() => undefined);
    // 4.6초 + 대기 0.5초 → 남은 4.9초 < 5초
    const deps = timedDeps([{ elapsedMs: 4_600, result: unavailable() }, { elapsedMs: 100, result: { text: '{"reply":"ok"}' } }]);
    const error = await expectReason(runAiTask(aiPingTask, {}, ctx(), deps), "AI_UNAVAILABLE");
    expect(error.upstreamStatus).toBe(503);
    expect(deps.calls).toHaveLength(1);
    expect(deps.sleeps).toEqual([]);
    const entry = info.mock.calls.find(([message]) => message === "ai_call")?.[1] as Record<string, unknown>;
    expect(entry).toMatchObject({ outcome: "AI_UNAVAILABLE", attempts: 1, retrySkipped: "budget" });
  });

  it("남은 시간이 딱 절반이면 재시도한다(경계)", async () => {
    // 4.5초 + 대기 0.5초 → 남은 5초 = 절반
    const deps = timedDeps([{ elapsedMs: 4_500, result: unavailable() }, { elapsedMs: 100, result: { text: '{"reply":"ok"}' } }]);
    expect((await runAiTask(aiPingTask, {}, ctx(), deps)).data.reply).toBe("ok");
    expect(deps.calls.map((call) => call.timeoutMs)).toEqual([budget, 5_000]);
  });

  it("형식 오류 재시도도 같은 시간 기준을 쓴다", async () => {
    const deps = timedDeps([{ elapsedMs: 6_000, result: { text: "not json" } }, { elapsedMs: 100, result: { text: '{"reply":"ok"}' } }]);
    await expectReason(runAiTask(aiPingTask, {}, ctx(), deps), "AI_INVALID_OUTPUT");
    expect(deps.calls).toHaveLength(1);
  });

  it("429 가 바로 오면 기존처럼 한 번 재시도한다", async () => {
    const deps = timedDeps([
      { elapsedMs: 200, result: new AiError("AI_RATE_LIMITED", "429", { transient: true, upstreamStatus: 429 }) },
      { elapsedMs: 1_000, result: { text: '{"reply":"ok"}' } },
    ]);
    expect((await runAiTask(aiPingTask, {}, ctx(), deps)).data.reply).toBe("ok");
    expect(deps.sleeps).toEqual([500]);
  });
});
