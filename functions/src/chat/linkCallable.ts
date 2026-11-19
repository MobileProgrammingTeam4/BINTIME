import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getDatabase } from "firebase-admin/database";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { z } from "zod";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { ChatLinkService } from "./link";

const input = z.object({ matchId: z.string().regex(/^[a-f0-9]{64}$/) }).strict();
function service() {
  const app = getApps().find(a => a.name === "[DEFAULT]") ?? initializeApp();
  return new ChatLinkService(getFirestore(app), getDatabase(app));
}
export function createChatLinkCallable(provider = service) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 60, maxInstances: 2 }, async request => {
    if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
    if (checkSchoolEmail(request.auth.token, parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]))) throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.");
    const parsed = input.safeParse(request.data);
    if (!parsed.success) throw new HttpsError("invalid-argument", "매칭 정보를 확인해주세요.");
    try { return await provider().link(parsed.data.matchId, request.auth.uid); }
    catch (error) {
      if (error instanceof HttpsError) throw error;
      throw new HttpsError("unavailable", "채팅방을 준비하지 못했어요. 잠시 후 다시 시도해주세요.");
    }
  });
}
export function createChatLinkTrigger() {
  // 자동 재시도 루프 대신 영속 작업 + 참여자의 명시적 재시도로 복구한다.
  return onDocumentCreated({ document: "matches/{matchId}", region: "asia-northeast3", retry: false,
    timeoutSeconds: 60, maxInstances: 2 }, async event => {
    try { await service().link(event.params.matchId); }
    catch { logger.error("chat_link_incomplete", { matchId: event.params.matchId }); }
  });
}
