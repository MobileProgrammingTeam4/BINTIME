import { randomUUID } from "node:crypto";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { cancelInput, createInput, respondInput } from "./contracts";
import type { ZodType } from "zod";
import { SearchCandidateResolver } from "./candidates";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { RequestService } from "./service";

function defaultService(): RequestService {
  const app = getApps().find((existing) => existing.name === "[DEFAULT]") ?? initializeApp();
  const db = getFirestore(app);
  return new RequestService(db, new SearchCandidateResolver(db));
}

const defaultSuffixes = () => parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]);

export function createRequestCallable(service: () => RequestService = defaultService,
  suffixes: () => readonly string[] = defaultSuffixes) {
  return requestCallable(createInput, (instance, uid, input) => instance.create(uid, input), service, suffixes);
}

export function cancelRequestCallable(service: () => RequestService = defaultService,
  suffixes: () => readonly string[] = defaultSuffixes) {
  return requestCallable(cancelInput, (instance, uid, input) => instance.cancel(uid, input.requestId), service, suffixes);
}

export function respondRequestCallable(service: () => RequestService = defaultService,
  suffixes: () => readonly string[] = defaultSuffixes) {
  return requestCallable(respondInput, (instance, uid, input) => instance.respond(uid, input), service, suffixes);
}

function requestCallable<T>(schema: ZodType<T>,
  execute: (instance: RequestService, uid: string, input: T) => ReturnType<RequestService["create"]>,
  service: () => RequestService, suffixes: () => readonly string[]) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 30 }, async (request) => {
    const requestId = randomUUID();
    try {
      if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.", { reason: "UNAUTHENTICATED" });
      if (checkSchoolEmail(request.auth.token, suffixes())) {
        throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.", { reason: "PERMISSION_DENIED" });
      }
      const parsed = schema.safeParse(request.data);
      if (!parsed.success) throw new HttpsError("invalid-argument", "요청 정보를 확인해주세요.", { reason: "INVALID_INPUT" });
      return await execute(service(), request.auth.uid, parsed.data);
    } catch (error) {
      if (error instanceof HttpsError) {
        throw new HttpsError(error.code, error.message, { ...(error.details as object), retryable: false, requestId });
      }
      throw new HttpsError("internal", "요청을 처리하지 못했습니다.", { reason: "INTERNAL", retryable: true, requestId });
    }
  });
}
