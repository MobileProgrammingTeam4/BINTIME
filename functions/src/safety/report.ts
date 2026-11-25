import { createHash } from "node:crypto";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { z } from "zod";

/**
 * B · BT-051 신고 접수 (docs/BT-051.md).
 *
 * - reports/{reportId}: 서버만 쓰고, 일반 사용자는 신고자·대상 모두 읽을 수 없다(Rules 기본 거부).
 *   검토는 콘솔·Admin 으로 한다.
 * - 중복 정책(팀 결정): 같은 신고자·대상·매칭(매칭 밖 신고는 matchId = null)당 1건.
 *   reportId 를 이 세 값의 해시로 고정해 같은 트랜잭션에서 막는다. 다시 보내면 ALREADY_REPORTED.
 * - matchId 가 있으면 두 사람이 그 매칭의 참여자여야 한다(남의 매칭으로 신고 기록을 만들지 못하게).
 *   matchId 가 없으면 대상 사용자가 있어야 한다(탈퇴한 사용자는 매칭 안에서만 신고 가능).
 * - 개인정보 최소 수집: 신고자·대상 UID, 매칭 ID, 사유, 직접 입력(최대 500자)만 저장한다. 이메일·이름·대화는 넣지 않는다.
 */

/** 앱 ReportReason 과 같은 값. 사유를 추가하면 앱 enum 과 함께 바꾼다. */
export const REPORT_REASONS = ["NO_SHOW", "NO_CONTACT", "ONE_SIDED_CANCEL", "ABUSIVE_LANGUAGE", "INAPPROPRIATE_MEETING",
  "RELIGION_MLM_PROMOTION", "INAPPROPRIATE_PROFILE", "IMPERSONATION", "SPAM"] as const;
export const DETAIL_MAX_LENGTH = 500;

export const reportInput = z.object({
  targetUid: z.string().min(1).max(128).regex(/^[A-Za-z0-9_-]+$/),
  matchId: z.string().regex(/^[a-f0-9]{64}$/).nullable(),
  reasons: z.array(z.enum(REPORT_REASONS)).min(1).max(REPORT_REASONS.length)
    .refine(list => new Set(list).size === list.length, "duplicate reasons"),
  detail: z.string().max(DETAIL_MAX_LENGTH).nullable(),
  alsoBlock: z.boolean(),
}).strict();
export type ReportInput = z.infer<typeof reportInput>;

export interface ReportResult { reportId: string; blocked: boolean }

const fail = (code: ConstructorParameters<typeof HttpsError>[0], reason: string, message: string) =>
  new HttpsError(code, message, { reason, retryable: false });

export const reportKey = (reporterUid: string, targetUid: string, matchId: string | null) =>
  createHash("sha256").update(JSON.stringify([reporterUid, targetUid, matchId])).digest("hex");

export class ReportService {
  constructor(private readonly db: Firestore, private readonly now: () => Date = () => new Date()) {}

  /** 신고를 저장하고 reportId 를 돌려준다. 함께 차단은 호출한 쪽(callable)이 저장 뒤에 한다. */
  async submit(uid: string, input: ReportInput): Promise<string> {
    if (uid === input.targetUid) throw fail("invalid-argument", "SELF_REPORT", "자기 자신은 신고할 수 없어요.");
    const detail = input.detail?.trim() ? input.detail.trim() : null;
    const reportId = reportKey(uid, input.targetUid, input.matchId);
    const ref = this.db.doc(`reports/${reportId}`);
    await this.db.runTransaction(async tx => {
      const [existing, match, target] = await Promise.all([
        tx.get(ref),
        input.matchId ? tx.get(this.db.doc(`matches/${input.matchId}`)) : Promise.resolve(null),
        input.matchId ? Promise.resolve(null) : tx.get(this.db.doc(`users/${input.targetUid}`)),
      ]);
      if (existing.exists) throw fail("already-exists", "ALREADY_REPORTED", "이미 신고한 내용이에요. 검토 중이에요.");
      if (match) {
        const participants = match.get("participantUids");
        if (!match.exists || !Array.isArray(participants) || !participants.includes(uid) || !participants.includes(input.targetUid)) {
          throw fail("permission-denied", "NOT_PARTICIPANT", "함께한 매칭의 상대만 신고할 수 있어요.");
        }
      } else if (!target?.exists) {
        throw fail("not-found", "USER_NOT_FOUND", "사용자를 찾을 수 없어요.");
      }
      tx.create(ref, {
        reporterUid: uid,
        targetUid: input.targetUid,
        matchId: input.matchId,
        reasons: [...new Set(input.reasons)].sort(),
        detail,
        alsoBlock: input.alsoBlock,
        status: "SUBMITTED",
        createdAt: Timestamp.fromDate(this.now()),
      });
    });
    return reportId;
  }
}
