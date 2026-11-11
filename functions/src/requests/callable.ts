import { randomUUID } from "node:crypto";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { createInput, disconnectedCandidates } from "./contracts";
import { RequestService } from "./service";

function defaultService(): RequestService {
  const app = getApps().find((existing) => existing.name === "[DEFAULT]") ?? initializeApp();
  return new RequestService(getFirestore(app), disconnectedCandidates);
}

export function createRequestCallable(service: () => RequestService = defaultService) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 30 }, async (request) => {
    const requestId = randomUUID();
    try {
      if (!request.auth) throw new HttpsError("unauthenticated", "로그인이 필요합니다.", { reason: "UNAUTHENTICATED" });
      const parsed = createInput.safeParse(request.data);
      if (!parsed.success) throw new HttpsError("invalid-argument", "요청 정보를 확인해주세요.", { reason: "INVALID_INPUT" });
      return await service().create(request.auth.uid, parsed.data);
    } catch (error) {
      if (error instanceof HttpsError) {
        throw new HttpsError(error.code, error.message, { ...(error.details as object), retryable: false, requestId });
      }
      throw new HttpsError("internal", "요청을 처리하지 못했습니다.", { reason: "INTERNAL", retryable: true, requestId });
    }
  });
}
