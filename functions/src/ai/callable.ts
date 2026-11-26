import { randomUUID } from "node:crypto";
import { onCall, type CallableOptions } from "firebase-functions/v2/https";
import { REGION } from "./config";
import { GEMINI_API_KEY, defaultDeps } from "./deps";
import { logger } from "firebase-functions";
import { checkSchoolEmail } from "./emailPolicy";
import { AiError, describeError, toHttpsError } from "./errors";
import { runAiTask } from "./gateway";
import type { AiDeps, AiResponse, AiTask } from "./types";

/**
 * 작업별로 바꿀 수 있는 배포 옵션. concurrency 를 넘기지 않으면 키를 넣지 않아 플랫폼 기본값(CPU 1 이상이면 80)을 쓴다.
 * 큰 요청을 메모리에 올리는 작업(analyzeTimetable)만 낮춘다.
 */
type ExtraOptions = Pick<CallableOptions, "timeoutSeconds" | "memory" | "concurrency">;

/**
 * 작업 하나를 앱용 Callable 로 연다. 인증은 Auth 토큰(request.auth)으로만 확인한다.
 * 로그인 직후 토큰의 email_verified 와 학교 이메일 접미사(ALLOWED_EMAIL_SUFFIXES)를 검사한다.
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
      ...(options.concurrency !== undefined ? { concurrency: options.concurrency } : {}),
      // 비용 폭주를 막는 상한. 필요하면 기능별로 조정한다.
      maxInstances: 10,
      // App Check inherits security/appCheck.ts; never override the shared policy here.
    },
    async (request) => {
      const requestId = randomUUID();
      try {
        if (!request.auth?.uid) throw new AiError("UNAUTHENTICATED");
        const resolved = deps();
        // 학교 이메일 검사는 사용량 차감(runAiTask) 전에 한다. 이메일 원문은 로그에 남기지 않는다.
        const denyCause = checkSchoolEmail(request.auth.token, resolved.allowedEmailSuffixes);
        if (denyCause) {
          logger.warn("ai_callable_denied", { task: task.name, requestId, uid: request.auth.uid, cause: denyCause });
          throw new AiError("PERMISSION_DENIED", denyCause);
        }
        return await runAiTask(task, request.data, { uid: request.auth.uid, requestId }, resolved);
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
