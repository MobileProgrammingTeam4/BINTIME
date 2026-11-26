import { logger } from "firebase-functions";
import { z } from "zod";
import { MAX_ATTEMPTS, MIN_RETRY_BUDGET_RATIO, RETRY_DELAY_MS, seoulDateKey } from "./config";
import { AiError, describeError } from "./errors";
import type { ModelUsage } from "./gemini";
import type { AiContext, AiDeps, AiResponse, AiTask, GeminiSchemaMode } from "./types";

/**
 * 모든 AI 기능의 공통 처리 순서.
 * 1 입력 검증 → 2 권한 검사 → 3 하루 호출량·최소 호출 간격 → 4 Mock 또는 Gemini 호출(제한 시간·재시도) → 5 응답 형식·규칙 검증 → 6 기록.
 * 인증(uid 확인)은 Callable 래퍼가 먼저 하고, 내부 호출은 서버가 uid 를 넣는다.
 */
export async function runAiTask<I, O>(
  task: AiTask<I, O>,
  rawInput: unknown,
  ctx: AiContext,
  deps: AiDeps,
): Promise<AiResponse<O>> {
  const startedAt = Date.now();
  if (!ctx.uid) throw new AiError("UNAUTHENTICATED");

  let outcome = "ok";
  let upstreamStatus: number | undefined;
  const stats: CallStats = { attempts: 0 };
  try {
    const input = parseInput(task, rawInput);
    if (task.authorize) await task.authorize(input, ctx, deps);

    const now = deps.now();
    const usage = await deps.usage.consume(
      ctx.uid,
      task.name,
      { dailyLimit: task.dailyLimit, minIntervalMs: task.minIntervalMs },
      seoulDateKey(now),
      now.getTime(),
    );
    if (usage === "DAILY_LIMIT") throw new AiError("QUOTA_EXCEEDED");
    // 연속 클릭·동시 요청. 잠시 후 다시 시도하면 되므로 retryable(AI_RATE_LIMITED 기본값)로 둔다.
    if (usage === "TOO_SOON") throw new AiError("AI_RATE_LIMITED", "min interval");

    let data: O;
    if (deps.mode === "mock") {
      data = checkOutput(task, task.mock(input, ctx), input, deps);
    } else {
      data = await callModel(task, input, ctx, deps, stats);
    }
    return {
      task: task.name,
      data,
      meta: {
        model: deps.mode === "mock" ? "mock" : modelFor(task, deps),
        requestId: ctx.requestId,
        latencyMs: Date.now() - startedAt,
        mock: deps.mode === "mock",
      },
    };
  } catch (error) {
    outcome = error instanceof AiError ? error.reason : "INTERNAL";
    upstreamStatus = error instanceof AiError ? error.upstreamStatus : undefined;
    if (!(error instanceof AiError)) {
      logger.error("ai_unexpected_error", { task: task.name, requestId: ctx.requestId, ...describeError(error) });
    }
    throw error;
  } finally {
    // 프롬프트·응답 원문과 개인정보는 남기지 않는다.
    logger.info("ai_call", {
      task: task.name,
      uid: ctx.uid,
      requestId: ctx.requestId,
      mode: deps.mode,
      model: deps.mode === "mock" ? "mock" : modelFor(task, deps),
      outcome,
      // 실패 원인 진단용. Gemini HTTP 상태 코드만 남기고 오류 본문은 남기지 않는다.
      upstreamStatus,
      attempts: stats.attempts,
      retrySkipped: stats.retrySkipped,
      latencyMs: Date.now() - startedAt,
      promptTokens: stats.usage?.promptTokens,
      outputTokens: stats.usage?.outputTokens,
      totalTokens: stats.usage?.totalTokens,
    });
  }
}

/** 작업이 쓸 모델 ID. 작업별 환경 변수가 비어 있으면 기본 모델(GEMINI_MODEL). */
export function modelFor<I, O>(task: AiTask<I, O>, deps: AiDeps): string {
  const env = task.geminiOptions?.modelEnv;
  const own = env ? (deps.taskModel ?? ((name: string) => process.env[name]))(env)?.trim() : undefined;
  return own || deps.model;
}

function parseInput<I, O>(task: AiTask<I, O>, rawInput: unknown): I {
  const parsed = task.input.safeParse(rawInput ?? {});
  if (!parsed.success) {
    const fields = [...new Set(parsed.error.issues.map((issue) => issue.path.join(".") || "(root)"))];
    throw new AiError("INVALID_INPUT", "input schema", { fields });
  }
  return parsed.data;
}

/** 스키마와 작업별 규칙을 모두 통과해야 앱으로 돌려준다. */
function checkOutput<I, O>(task: AiTask<I, O>, candidate: unknown, input: I, deps: AiDeps): O {
  const parsed = task.output.safeParse(candidate);
  if (!parsed.success) throw new AiError("AI_INVALID_OUTPUT", "output schema", { transient: true });
  return task.verify ? task.verify(parsed.data, input, deps) : parsed.data;
}

interface CallStats {
  attempts: number;
  usage?: ModelUsage;
  /** 일시적 오류였지만 다시 시도하지 않은 이유. budget: 남은 시간이 제한 시간의 절반보다 적었다. */
  retrySkipped?: "budget";
}

async function callModel<I, O>(task: AiTask<I, O>, input: I, ctx: AiContext, deps: AiDeps, stats: CallStats): Promise<O> {
  if (!task.buildRequest) throw new AiError("AI_NOT_IMPLEMENTED", `${task.name} has no live prompt yet`);
  const model = modelFor(task, deps);
  if (!model) throw new AiError("AI_UNAVAILABLE", "GEMINI_MODEL is not set");
  const { mediaResolution, thinkingLevel } = task.geminiOptions ?? {};

  const request = await task.buildRequest(input, ctx, deps);
  const client = deps.modelClient();
  const jsonSchema = toGeminiSchema(task.output, task.geminiSchema);

  // task.timeoutMs 는 재시도까지 포함한 전체 시간이다(#48). 두 번째 시도는 남은 시간만 쓴다.
  const clock = deps.clockMs ?? Date.now;
  const deadline = clock() + task.timeoutMs;
  let lastError: AiError = new AiError("AI_UNAVAILABLE");
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    stats.attempts = attempt;
    const timeoutMs = attempt === 1 ? task.timeoutMs : Math.max(1, deadline - clock());
    try {
      const result = await client.generate({ ...request, model, mediaResolution, thinkingLevel, jsonSchema, timeoutMs });
      stats.usage = result.usage;
      let json: unknown;
      try {
        json = JSON.parse(result.text ?? "");
      } catch {
        throw new AiError("AI_INVALID_OUTPUT", "not json", { transient: true });
      }
      return checkOutput(task, json, input, deps);
    } catch (error) {
      lastError = error instanceof AiError ? error : new AiError("INTERNAL", String(error));
      // 시간 초과(우리 타이머·Google 마감 504)는 transient 가 아니라 재시도하지 않는다.
      if (!lastError.transient || attempt === MAX_ATTEMPTS) break;
      // 기다린 뒤 남을 시간이 제한 시간의 절반보다 적으면 다시 보내도 시간 초과로 끝나기 쉬우므로 첫 오류를 그대로 돌려준다.
      if (deadline - clock() - RETRY_DELAY_MS < task.timeoutMs * MIN_RETRY_BUDGET_RATIO) {
        stats.retrySkipped = "budget";
        break;
      }
      await deps.sleep(RETRY_DELAY_MS);
    }
  }
  throw lastError;
}

/**
 * Gemini responseJsonSchema 로 보낼 JSON Schema. $schema 같은 메타 키는 뺀다.
 * mode "typesOnly" 는 값 제약({@link VALUE_CONSTRAINT_KEYS})을 모든 하위 스키마에서 뺀다(AiTask.geminiSchema 참고).
 */
export function toGeminiSchema(schema: z.ZodType, mode: GeminiSchemaMode = "full"): unknown {
  const json = z.toJSONSchema(schema) as Record<string, unknown>;
  delete json.$schema;
  if (mode === "typesOnly") stripValueConstraints(json);
  return json;
}

/** "typesOnly" 에서 빼는 값 제약 키워드. type·required·anyOf·additionalProperties 같은 구조는 남긴다. */
const VALUE_CONSTRAINT_KEYS = [
  "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
  "minLength", "maxLength", "pattern", "format",
  "minItems", "maxItems",
] as const;

/**
 * 스키마 노드만 따라가며 값 제약 키워드를 지운다. properties 의 키는 필드 이름이므로
 * (예: 필드 이름이 "maximum" 이어도) 지우지 않고 그 값(하위 스키마)만 따라간다.
 */
function stripValueConstraints(node: unknown): void {
  if (!node || typeof node !== "object" || Array.isArray(node)) return;
  const schema = node as Record<string, unknown>;
  for (const key of VALUE_CONSTRAINT_KEYS) delete schema[key];
  for (const key of ["properties", "$defs", "definitions"]) {
    const map = schema[key];
    if (map && typeof map === "object") Object.values(map).forEach(stripValueConstraints);
  }
  for (const key of ["items", "additionalProperties", "not"]) stripValueConstraints(schema[key]);
  for (const key of ["anyOf", "oneOf", "allOf", "prefixItems"]) {
    const list = schema[key];
    if (Array.isArray(list)) list.forEach(stripValueConstraints);
  }
}
