import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { z } from "zod";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { ChatLeaveService } from "./leave";

/** 입력은 실제 RTDB 방 키 하나(BT-034: 최초 매칭 ID, 64자리 hex). UID·상태·멤버는 받지 않는다. */
export const leaveInput = z.object({ chatRoomId: z.string().regex(/^[a-f0-9]{64}$/) }).strict();

function service() {
  const app = getApps().find(a => a.name === "[DEFAULT]") ?? initializeApp();
  return new ChatLeaveService(getFirestore(app), getDatabase(app));
}

/** E · BT-043. 오류: unauthenticated / permission-denied / invalid-argument / failed-precondition{reason: ACTIVE_MATCH|RETRY_LATER|INVALID_ROOM}. */
export function createChatLeaveCallable(provider = service) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 60, maxInstances: 2 }, async request => {
    if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
    if (checkSchoolEmail(request.auth.token, parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]))) throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.");
    const parsed = leaveInput.safeParse(request.data);
    if (!parsed.success) throw new HttpsError("invalid-argument", "채팅방 정보를 확인해주세요.");
    try { return await provider().leave(parsed.data.chatRoomId, request.auth.uid); }
    catch (error) {
      if (error instanceof HttpsError) throw error;
      logger.error("chat_leave_unexpected", { chatRoomId: parsed.data.chatRoomId });
      throw new HttpsError("failed-precondition", "채팅방을 나가지 못했어요.", { reason: "RETRY_LATER", retryAfterMillis: 10_000 });
    }
  });
}
