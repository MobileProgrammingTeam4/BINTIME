import { randomUUID } from "node:crypto";
import { onCall, type CallableOptions } from "firebase-functions/v2/https";
import { REGION } from "./config";
import { GEMINI_API_KEY, defaultDeps } from "./deps";
import { logger } from "firebase-functions";
import { AiError, describeError, toHttpsError } from "./errors";
import { runAiTask } from "./gateway";
import type { AiDeps, AiResponse, AiTask } from "./types";

type ExtraOptions = Pick<CallableOptions, "timeoutSeconds" | "memory">;

/**
 * 작업 하나를 앱용 Callable 로 연다. 인증은 Auth 토큰(request.auth)으로만 확인한다.
 * 모든 오류는 HttpsError(details: { reason, retryable, requestId }) 로 바꿔 보낸다.
 */
export function createAiCallable<I, O>(
  task: AiTask<I, O>,
  options: ExtraOptions = {},
  deps: () => AiDeps = defaultDeps,
) {
  return onCall<unknown, Promise<AiResponse<O>>>(
    {
      region: REGION,
      secrets: [GEMINI_API_KEY],
      timeoutSeconds: options.timeoutSeconds ?? 30,
      memory: options.memory ?? "256MiB",
      // 비용 폭주를 막는 상한. 필요하면 기능별로 조정한다.
      maxInstances: 10,
      // App Check 는 앱 등록 후 별도 이슈에서 켠다.
      enforceAppCheck: false,
    },
    async (request) => {
      const requestId = randomUUID();
      try {
        if (!request.auth?.uid) throw new AiError("UNAUTHENTICATED");
        return await runAiTask(task, request.data, { uid: request.auth.uid, requestId }, deps());
      } catch (error) {
        // runAiTask 밖(의존성 준비 등)에서 난 예상하지 못한 오류도 원인을 남긴다. 앱에는 INTERNAL 만 보낸다.
        if (!(error instanceof AiError)) {
          logger.error("ai_callable_unexpected_error", { task: task.name, requestId, ...describeError(error) });
        }
        throw toHttpsError(error, requestId);
      }
    },
  );
}
