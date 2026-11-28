import { randomUUID } from "node:crypto";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { getInput, ReviewService, submitInput } from "./review";

/** E · BT-044 submitReview({matchId, satisfaction, keywords, comment}) / getMatchReview({matchId}). asia-northeast3. */
function defaultService(): ReviewService {
  const app = getApps().find((existing) => existing.name === "[DEFAULT]") ?? initializeApp();
  return new ReviewService(getFirestore(app));
}

const defaultSuffixes = () => parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]);

export function createSubmitReviewCallable(service: () => ReviewService = defaultService,
  suffixes: () => readonly string[] = defaultSuffixes) {
  return reviewCallable("submitReview", submitInput, (instance, uid, input) => instance.submit(uid, input), service, suffixes);
}

export function createGetMatchReviewCallable(service: () => ReviewService = defaultService,
  suffixes: () => readonly string[] = defaultSuffixes) {
  return reviewCallable("getMatchReview", getInput, (instance, uid, input) => instance.get(uid, input.matchId), service, suffixes);
}

interface Parser<T> { safeParse(data: unknown): { success: true; data: T } | { success: false } }

function reviewCallable<T, R>(name: string, schema: Parser<T>,
  execute: (instance: ReviewService, uid: string, input: T) => Promise<R>,
  service: () => ReviewService, suffixes: () => readonly string[]) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 30, maxInstances: 5 }, async (request) => {
    const requestId = randomUUID();
    try {
      if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.", { reason: "UNAUTHENTICATED" });
      if (checkSchoolEmail(request.auth.token, suffixes())) {
        throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.", { reason: "PERMISSION_DENIED" });
      }
      const parsed = schema.safeParse(request.data);
      if (!parsed.success) throw new HttpsError("invalid-argument", "후기 정보를 확인해주세요.", { reason: "INVALID_INPUT" });
      return await execute(service(), request.auth.uid, parsed.data);
    } catch (error) {
      if (error instanceof HttpsError) {
        throw new HttpsError(error.code, error.message, { ...(error.details as object), requestId });
      }
      // 후기 내용·UID 는 남기지 않는다.
      logger.error("review_unexpected_error", { fn: name, requestId, error: error instanceof Error ? error.name : "unknown" });
      throw new HttpsError("unavailable", "후기를 처리하지 못했어요. 잠시 후 다시 시도해주세요.", { reason: "RETRY_LATER", requestId });
    }
  });
}
