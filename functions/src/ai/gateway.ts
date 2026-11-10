import { logger } from "firebase-functions";
import { z } from "zod";
import { MAX_ATTEMPTS, RETRY_DELAY_MS, seoulDateKey } from "./config";
import { AiError, describeError } from "./errors";
import type { ModelUsage } from "./gemini";
import type { AiContext, AiDeps, AiResponse, AiTask } from "./types";

/**
 * 모든 AI 기능의 공통 처리 순서.
 * 1 입력 검증 → 2 권한 검사 → 3 하루 호출량 → 4 Mock 또는 Gemini 호출(제한 시간·재시도) → 5 응답 형식·규칙 검증 → 6 기록.
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

    const allowed = await deps.usage.consume(ctx.uid, task.name, task.dailyLimit, seoulDateKey(deps.now()));
    if (!allowed) throw new AiError("QUOTA_EXCEEDED");

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
        model: deps.mode === "mock" ? "mock" : deps.model,
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
      model: deps.mode === "mock" ? "mock" : deps.model,
      outcome,
      // 실패 원인 진단용. Gemini HTTP 상태 코드만 남기고 오류 본문은 남기지 않는다.
      upstreamStatus,
      attempts: stats.attempts,
      latencyMs: Date.now() - startedAt,
      promptTokens: stats.usage?.promptTokens,
      outputTokens: stats.usage?.outputTokens,
      totalTokens: stats.usage?.totalTokens,
    });
  }
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
}

async function callModel<I, O>(task: AiTask<I, O>, input: I, ctx: AiContext, deps: AiDeps, stats: CallStats): Promise<O> {
  if (!task.buildRequest) throw new AiError("AI_NOT_IMPLEMENTED", `${task.name} has no live prompt yet`);
  if (!deps.model) throw new AiError("AI_UNAVAILABLE", "GEMINI_MODEL is not set");

  const request = await task.buildRequest(input, ctx, deps);
  const client = deps.modelClient();
  const jsonSchema = toGeminiSchema(task.output);

  let lastError: AiError = new AiError("AI_UNAVAILABLE");
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    stats.attempts = attempt;
    try {
      const result = await client.generate({ ...request, model: deps.model, jsonSchema, timeoutMs: task.timeoutMs });
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
      // 시간 초과는 다시 기다리면 전체 응답이 두 배로 늦어지므로 재시도하지 않는다.
      if (!lastError.transient || attempt === MAX_ATTEMPTS) break;
      await deps.sleep(RETRY_DELAY_MS);
    }
  }
  throw lastError;
}

/** Gemini responseJsonSchema 로 보낼 JSON Schema. $schema 같은 메타 키는 뺀다. */
export function toGeminiSchema(schema: z.ZodType): unknown {
  const json = z.toJSONSchema(schema) as Record<string, unknown>;
  delete json.$schema;
  return json;
}
