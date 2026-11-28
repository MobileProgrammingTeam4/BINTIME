import { logger } from "firebase-functions";
import { afterEach, describe, expect, it, vi } from "vitest";
import { modelFor, runAiTask } from "../src/ai/gateway";
import { generateParams, type ModelCall } from "../src/ai/gemini";
import { aiPingTask } from "../src/ai/tasks/aiPing";
import { analyzeTimetableTask } from "../src/ai/tasks/analyzeTimetable";
import { evaluateMatchTask } from "../src/ai/tasks/evaluateMatch";
import { generateProfileTask } from "../src/ai/tasks/generateProfile";
import { recommendPlaceTask } from "../src/ai/tasks/recommendPlace";
import { suggestChatRepliesTask } from "../src/ai/tasks/suggestChatReplies";
import type { AiTask } from "../src/ai/types";
import { ctx, makeDeps } from "./helpers";

/** BT-048: 작업별 모델·이미지 해상도·thinking 설정. */
const baseCall = (overrides: Partial<ModelCall> = {}): ModelCall => ({
  model: "m",
  systemInstruction: "sys",
  contents: [{ role: "user", parts: [{ text: "읽어 줘" }, { inlineData: { mimeType: "image/jpeg", data: "AAAA" } }] }],
  temperature: 0,
  maxOutputTokens: 100,
  jsonSchema: { type: "object" },
  timeoutMs: 1000,
  ...overrides,
});

describe("generateParams", () => {
  it("옵션이 없으면 옵션 도입 전과 같은 요청(해상도·thinkingConfig 없음, contents 그대로)", () => {
    const call = baseCall();
    const params = generateParams(call);
    expect(params.contents).toBe(call.contents);
    expect(params.config).not.toHaveProperty("thinkingConfig");
    expect(params.config).not.toHaveProperty("mediaResolution");
    expect(JSON.stringify(params.contents)).not.toContain("mediaResolution");
    expect(params.config).toMatchObject({ systemInstruction: "sys", temperature: 0, maxOutputTokens: 100,
      responseMimeType: "application/json", responseJsonSchema: { type: "object" }, httpOptions: { timeout: 1000 } });
  });

  it("ULTRA_HIGH 는 이미지 part 에만 붙이고 텍스트 part·원본 contents 는 바꾸지 않는다", () => {
    const call = baseCall({ mediaResolution: "ULTRA_HIGH" });
    const before = JSON.stringify(call.contents);
    const parts = (generateParams(call).contents as Array<{ parts: Array<Record<string, unknown>> }>)[0].parts;
    expect(parts[0]).toEqual({ text: "읽어 줘" });
    expect(parts[1]).toMatchObject({ mediaResolution: { level: "MEDIA_RESOLUTION_ULTRA_HIGH" } });
    expect(JSON.stringify(call.contents)).toBe(before);
    expect(generateParams(call).config).not.toHaveProperty("mediaResolution"); // config 단위로는 보내지 않는다
  });

  it("thinkingLevel 은 thinkingConfig 로 보낸다", () => {
    expect(generateParams(baseCall({ thinkingLevel: "LOW" })).config?.thinkingConfig).toEqual({ thinkingLevel: "LOW" });
  });
});

describe("작업별 모델 선택", () => {
  afterEach(() => vi.restoreAllMocks());

  const ownModelTask: AiTask<{ message?: string }, { reply: string }> = {
    ...aiPingTask,
    geminiOptions: { modelEnv: "GEMINI_MODEL_TEST_TASK", mediaResolution: "ULTRA_HIGH", thinkingLevel: "MINIMAL" },
  };
  const pong = { text: JSON.stringify({ reply: "pong" }) };

  it("작업별 환경 변수가 있으면 그 모델로 부르고 응답 meta·로그에도 그 모델을 남긴다", async () => {
    const info = vi.spyOn(logger, "info").mockImplementation(() => undefined);
    const deps = makeDeps({ mode: "live", model: "default-model", steps: [pong] });
    deps.taskModel = (name) => (name === "GEMINI_MODEL_TEST_TASK" ? " own-model " : undefined);
    const res = await runAiTask(ownModelTask, {}, ctx(), deps);
    expect(deps.client.calls[0]).toMatchObject({ model: "own-model", mediaResolution: "ULTRA_HIGH", thinkingLevel: "MINIMAL" });
    expect(res.meta.model).toBe("own-model");
    expect(info).toHaveBeenCalledWith("ai_call", expect.objectContaining({ model: "own-model" }));
  });

  it("작업별 환경 변수가 비어 있으면 기본 모델(GEMINI_MODEL)", async () => {
    const deps = makeDeps({ mode: "live", model: "default-model", steps: [pong] });
    deps.taskModel = () => "  ";
    await runAiTask(ownModelTask, {}, ctx(), deps);
    expect(deps.client.calls[0].model).toBe("default-model");
  });

  it("옵션이 없는 작업은 기본 모델이고 해상도·thinking 을 보내지 않는다", async () => {
    const deps = makeDeps({ mode: "live", model: "default-model", steps: [pong] });
    deps.taskModel = () => "should-not-be-used";
    await runAiTask(aiPingTask, {}, ctx(), deps);
    expect(deps.client.calls[0].model).toBe("default-model");
    expect(deps.client.calls[0].mediaResolution).toBeUndefined();
    expect(deps.client.calls[0].thinkingLevel).toBeUndefined();
  });

  it("analyzeTimetable 만 전용 모델 변수 + ULTRA_HIGH(thinking 기본값), 다른 AI 기능은 옵션 없음", () => {
    expect(analyzeTimetableTask.geminiOptions).toEqual({ modelEnv: "GEMINI_MODEL_ANALYZE_TIMETABLE", mediaResolution: "ULTRA_HIGH" });
    for (const task of [aiPingTask, generateProfileTask, evaluateMatchTask, recommendPlaceTask, suggestChatRepliesTask]) {
      expect(task.geminiOptions, task.name).toBeUndefined();
    }
    const deps = makeDeps({ mode: "live", model: "default-model" });
    deps.taskModel = (name) => (name === "GEMINI_MODEL_ANALYZE_TIMETABLE" ? "gemini-3.8-flash" : undefined);
    expect(modelFor(analyzeTimetableTask, deps)).toBe("gemini-3.8-flash");
    expect(modelFor(suggestChatRepliesTask, deps)).toBe("default-model");
  });
});
