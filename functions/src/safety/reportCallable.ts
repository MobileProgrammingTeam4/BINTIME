import { randomUUID } from "node:crypto";
import { getApps, initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";
import { getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { BlockService } from "./block";
import { reportInput, ReportService, type ReportResult } from "./report";

export interface ReportDeps { reports: ReportService; blocks: Pick<BlockService, "block"> }

function defaultDeps(): ReportDeps {
  const app = getApps().find(existing => existing.name === "[DEFAULT]") ?? initializeApp();
  const db = getFirestore(app);
  return { reports: new ReportService(db), blocks: new BlockService(db, getDatabase(app)) };
}

const defaultSuffixes = () => parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]);

/**
 * B · BT-051 submitReport({targetUid, matchId, reasons, detail, alsoBlock}). 신고자는 토큰 UID 로만 정한다.
 * 함께 차단은 신고를 저장한 뒤 BT-046 blockUser 와 같은 서비스로 한다. 차단만 실패하면 신고는 그대로 두고 blocked=false.
 * 이미 신고한 경우에도 함께 차단을 고르면 차단은 해 준다(차단 진입점이 신고 화면뿐이라). 오류 details.blocked 로 알린다.
 */
export function createReportCallable(deps = defaultDeps, suffixes: () => readonly string[] = defaultSuffixes) {
  return onCall({ region: "asia-northeast3", timeoutSeconds: 30, maxInstances: 2 }, async (request): Promise<ReportResult> => {
    const requestId = randomUUID();
    if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.", { reason: "UNAUTHENTICATED", requestId });
    if (checkSchoolEmail(request.auth.token, suffixes())) {
      throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.", { reason: "PERMISSION_DENIED", requestId });
    }
    const parsed = reportInput.safeParse(request.data);
    if (!parsed.success) throw new HttpsError("invalid-argument", "신고 내용을 확인해주세요.", { reason: "INVALID_INPUT", requestId });
    const uid = request.auth.uid, input = parsed.data;
    const { reports, blocks } = deps();
    const block = async () => {
      if (!input.alsoBlock) return false;
      try { await blocks.block(uid, input.targetUid); return true; }
      catch (error) {
        logger.warn("report_block_failed", { requestId, code: String((error as { code?: unknown })?.code ?? "unknown") });
        return false;
      }
    };
    let reportId: string;
    try {
      reportId = await reports.submit(uid, input);
    } catch (error) {
      if (error instanceof HttpsError) {
        const blocked = error.details && (error.details as { reason?: string }).reason === "ALREADY_REPORTED" ? await block() : false;
        throw new HttpsError(error.code, error.message, { ...(error.details as object), blocked, requestId });
      }
      logger.error("report_submit_failed", { requestId, code: String((error as { code?: unknown })?.code ?? "unknown") });
      throw new HttpsError("unavailable", "신고를 접수하지 못했어요. 잠시 후 다시 시도해주세요.",
        { reason: "INTERNAL", retryable: true, requestId });
    }
    return { reportId, blocked: await block() };
  });
}
