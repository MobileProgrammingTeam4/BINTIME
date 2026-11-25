import { randomUUID } from "node:crypto";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getDatabase } from "firebase-admin/database";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { z } from "zod";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { AccountDeletionBusy, AccountDeletionService } from "./deleteAccount";

/** 입력 없음. 탈퇴할 계정은 토큰 UID 로만 정한다. */
const input = z.union([z.null(), z.undefined(), z.object({}).strict()]);

function defaultService(): AccountDeletionService {
  const app = getApps().find(existing => existing.name === "[DEFAULT]") ?? initializeApp();
  const auth = getAuth(app);
  const bucket = getStorage(app).bucket();
  return new AccountDeletionService(getFirestore(app), getDatabase(app), {
    async deleteUserFiles(uid) {
      for (const prefix of [`profileImages/${uid}/`, `timetableImages/${uid}/`]) await bucket.deleteFiles({ prefix });
    },
  }, {
    async deleteUser(uid) {
      try { await auth.deleteUser(uid); }
      catch (error) { if ((error as { code?: string }).code !== "auth/user-not-found") throw error; }
    },
  });
}

const defaultSuffixes = () => parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]);

/** B · BT-045 deleteAccount(). 실패하면 retryable 로 알리고, 같은 사용자가 다시 부르면 남은 단계를 이어서 한다. */
export function createDeleteAccountCallable(service = defaultService, suffixes: () => readonly string[] = defaultSuffixes) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 120, maxInstances: 2 }, async request => {
    const requestId = randomUUID();
    if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.", { reason: "UNAUTHENTICATED", requestId });
    if (checkSchoolEmail(request.auth.token, suffixes())) {
      throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.", { reason: "PERMISSION_DENIED", requestId });
    }
    if (!input.safeParse(request.data).success) {
      throw new HttpsError("invalid-argument", "요청 정보를 확인해주세요.", { reason: "INVALID_INPUT", requestId });
    }
    try {
      return await service().delete(request.auth.uid);
    } catch (error) {
      if (error instanceof AccountDeletionBusy) {
        throw new HttpsError("unavailable", "채팅방을 정리하는 중이에요. 잠시 후 다시 시도해주세요.",
          { reason: "CHAT_LINK_BUSY", retryable: true, retryAfterMillis: error.retryAfterMillis, requestId });
      }
      // UID·개인정보는 남기지 않는다. 운영자는 accountDeletions 문서와 requestId 로 추적한다.
      // 오류 메시지에는 문서 경로(UID)가 들어갈 수 있어 코드만 남긴다.
      logger.error("account_delete_failed", { requestId, code: String((error as { code?: unknown })?.code ?? "unknown") });
      throw new HttpsError("unavailable", "탈퇴를 마치지 못했어요. 잠시 후 다시 시도해주세요.",
        { reason: "INTERNAL", retryable: true, requestId });
    }
  });
}
