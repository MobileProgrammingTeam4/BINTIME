import { randomUUID } from "node:crypto";
import { getApps, initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";
import { getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { blockInput, BlockService, type BlockResult } from "./block";

function defaultService(): BlockService {
  const app = getApps().find(existing => existing.name === "[DEFAULT]") ?? initializeApp();
  return new BlockService(getFirestore(app), getDatabase(app));
}

const defaultSuffixes = () => parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]);

/** B · BT-046 blockUser({targetUid}). 차단하는 사람은 토큰 UID 로만 정한다. */
export function createBlockCallable(service = defaultService, suffixes: () => readonly string[] = defaultSuffixes) {
  return blockCallable((instance, uid, targetUid) => instance.block(uid, targetUid), service, suffixes);
}

/** B · BT-046 unblockUser({targetUid}). 내 차단 기록만 지운다. */
export function createUnblockCallable(service = defaultService, suffixes: () => readonly string[] = defaultSuffixes) {
  return blockCallable((instance, uid, targetUid) => instance.unblock(uid, targetUid), service, suffixes);
}

function blockCallable(execute: (instance: BlockService, uid: string, targetUid: string) => Promise<BlockResult>,
  service: () => BlockService, suffixes: () => readonly string[]) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 30, maxInstances: 2 }, async request => {
    const requestId = randomUUID();
    try {
      if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.", { reason: "UNAUTHENTICATED" });
      if (checkSchoolEmail(request.auth.token, suffixes())) {
        throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.", { reason: "PERMISSION_DENIED" });
      }
      const parsed = blockInput.safeParse(request.data);
      if (!parsed.success) throw new HttpsError("invalid-argument", "요청 정보를 확인해주세요.", { reason: "INVALID_INPUT" });
      return await execute(service(), request.auth.uid, parsed.data.targetUid);
    } catch (error) {
      if (error instanceof HttpsError) {
        throw new HttpsError(error.code, error.message, { ...(error.details as object), retryable: false, requestId });
      }
      // Firestore 경합·RTDB 반영 실패. 같은 요청을 다시 보내면 이어서 반영한다.
      throw new HttpsError("unavailable", "잠시 후 다시 시도해주세요.", { reason: "INTERNAL", retryable: true, requestId });
    }
  });
}
