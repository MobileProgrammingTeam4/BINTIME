import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getDatabase } from "firebase-admin/database";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { z } from "zod";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { RoomStateService } from "../chat/roomState";
import { MatchEndService } from "./endMatch";

const input = z.object({ matchId: z.string().regex(/^[a-f0-9]{64}$/) }).strict();
export function endService() {
  const app = getApps().find(a => a.name === "[DEFAULT]") ?? initializeApp();
  return new MatchEndService(getFirestore(app), new RoomStateService(getFirestore(app), getDatabase(app)));
}
export function createEndMatchCallable(provider = endService) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 60, maxInstances: 2 }, async request => {
    if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
    if (checkSchoolEmail(request.auth.token, parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]))) throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.");
    const parsed = input.safeParse(request.data);
    if (!parsed.success) throw new HttpsError("invalid-argument", "매칭 정보를 확인해주세요.");
    return provider().end(parsed.data.matchId, request.auth.uid);
  });
}
/** Code only: production Scheduler activation/deployment needs separate approval. */
export function createMatchEndSchedule() {
  return onSchedule({ schedule: "every 1 minutes", timeZone: "Asia/Seoul", region: "asia-northeast3",
    maxInstances: 1, minInstances: 0, timeoutSeconds: 60, retryCount: 0 }, async () => {
    const result = await endService().runBatch();
    if (result.failed) logger.error("match_end_partial_failure", result);
    else logger.info("match_end_completed", result);
  });
}
