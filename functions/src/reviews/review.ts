import { Timestamp, type DocumentSnapshot, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { z } from "zod";

/**
 * E · BT-044 매칭 후기. 저장 경로는 reviews/{matchId}_{authorUid}(매칭·작성자당 1건).
 *
 * - reviews 는 Firestore Rules 에 규칙이 없어 앱은 읽기·쓰기 모두 거부된다. 쓰기·읽기 모두 이 서비스(Callable)만 한다.
 * - 작성: 참여자·ENDED·reviewEligible === true(BT-038 서버 확정 값)·상대 미탈퇴(accountDeletions)를 다시 검사한다.
 *   endReason=ACCOUNT_DELETED(#130)는 reviewEligible=false 라 여기서 거부된다. 앱이 보낸 자격·상대 UID 는 받지 않는다.
 * - 조회: 내 후기는 항상, 상대 후기는 내 후기가 있을 때만 돌려준다. 상대 후기가 없으면 NOT_YET.
 * - 문서에 matchId·authorUid·targetUid 를 그대로 둔다. BT-039 notificationOnReviewCreated(D)가 이 세 필드만 읽는다.
 * - 쓴 후기는 고치거나 지우지 않는다. 탈퇴자 후기 처리는 미정(B 와 결정, docs/BT-044.md).
 */

export const SATISFACTIONS = ["BAD", "GOOD", "GREAT"] as const;
/** 앱 review/ReviewKeyword enum 이름과 같다. */
export const REVIEW_KEYWORDS = ["ON_TIME", "QUICK_REPLY", "CLEAN_PARTING", "KIND_MANNER", "FOCUSED_STUDY",
  "QUIET_TOGETHER", "MAJOR_TALK", "GOOD_CONVERSATION", "COMFORTABLE_MOOD", "CAMPUS_TIPS"] as const;
export const COMMENT_MAX_LENGTH = 100;

const MATCH_ID = z.string().regex(/^[a-f0-9]{64}$/);

export const submitInput = z.object({
  matchId: MATCH_ID,
  satisfaction: z.enum(SATISFACTIONS),
  keywords: z.array(z.enum(REVIEW_KEYWORDS)).max(REVIEW_KEYWORDS.length)
    .refine((list) => new Set(list).size === list.length, "duplicate keyword"),
  // 앞뒤 공백을 지우고 비었으면 null. 연락처는 지우지 않는다(이미 채팅하는 상대, 팀 결정).
  comment: z.string().max(1000).nullable().optional()
    .transform((value) => (value ?? "").trim() || null)
    .refine((value) => value === null || value.length <= COMMENT_MAX_LENGTH, "comment too long"),
}).strict();
export type SubmitInput = z.infer<typeof submitInput>;

export const getInput = z.object({ matchId: MATCH_ID }).strict();

export type Visibility = "LOCKED" | "NOT_YET" | "VISIBLE";

export interface ReviewView {
  satisfaction: (typeof SATISFACTIONS)[number];
  keywords: string[];
  comment: string | null;
  createdAt: number;
}

export interface MatchReviewResult {
  matchId: string;
  partnerUid: string;
  /** 지금 작성할 수 있는지(ENDED·reviewEligible·상대 미탈퇴·아직 안 씀) */
  canWrite: boolean;
  meetingDate: string | null;
  startMinute: number | null;
  endMinute: number | null;
  mine: ReviewView | null;
  received: { visibility: Visibility; review: ReviewView | null };
}

export const reviewId = (matchId: string, authorUid: string) => `${matchId}_${authorUid}`;

const fail = (reason: string, message: string) => new HttpsError("failed-precondition", message, { reason });
const denied = () => new HttpsError("permission-denied", "이 매칭의 후기를 볼 수 없어요.", { reason: "PERMISSION_DENIED" });

interface Participants { partnerUid: string }

/** 참여자 두 명 중 내가 있는지. 매칭이 없거나 형식이 틀리면 존재를 알리지 않고 거부한다. */
function participants(match: DocumentSnapshot, uid: string): Participants {
  const uids = match.exists ? match.get("participantUids") : null;
  if (!Array.isArray(uids) || uids.length !== 2 || uids.some((u) => typeof u !== "string") || uids[0] === uids[1]) throw denied();
  if (!uids.includes(uid)) throw denied();
  return { partnerUid: uids.find((u) => u !== uid) as string };
}

function eligible(match: DocumentSnapshot): boolean {
  return match.get("status") === "ENDED" && match.get("reviewEligible") === true;
}

function view(doc: DocumentSnapshot): ReviewView | null {
  if (!doc.exists) return null;
  const createdAt = doc.get("createdAt");
  const keywords = doc.get("keywords");
  const comment = doc.get("comment");
  return {
    satisfaction: doc.get("satisfaction"),
    keywords: Array.isArray(keywords) ? keywords.filter((k) => typeof k === "string") : [],
    comment: typeof comment === "string" ? comment : null,
    createdAt: createdAt instanceof Timestamp ? createdAt.toMillis() : 0,
  };
}

export class ReviewService {
  constructor(private readonly db: Firestore, private readonly now: () => Date = () => new Date()) {}

  async submit(uid: string, input: SubmitInput): Promise<{ matchId: string; written: true }> {
    const matchRef = this.db.doc(`matches/${input.matchId}`);
    const reviewRef = this.db.doc(`reviews/${reviewId(input.matchId, uid)}`);
    await this.db.runTransaction(async (tx) => {
      const match = await tx.get(matchRef);
      const { partnerUid } = participants(match, uid);
      const [mine, meDeleted, partnerDeleted] = await Promise.all([
        tx.get(reviewRef),
        tx.get(this.db.doc(`accountDeletions/${uid}`)),
        tx.get(this.db.doc(`accountDeletions/${partnerUid}`)),
      ]);
      if (meDeleted.exists) throw denied();
      if (mine.exists) throw fail("ALREADY_REVIEWED", "이미 후기를 남겼어요.");
      if (!eligible(match)) throw fail("NOT_ELIGIBLE", "후기를 남길 수 없는 매칭이에요.");
      if (partnerDeleted.exists) throw fail("PARTNER_DELETED", "탈퇴한 사용자에게는 후기를 남길 수 없어요.");
      tx.create(reviewRef, {
        matchId: input.matchId,
        authorUid: uid,
        targetUid: partnerUid,
        satisfaction: input.satisfaction,
        keywords: REVIEW_KEYWORDS.filter((k) => input.keywords.includes(k)),
        comment: input.comment ?? null,
        createdAt: Timestamp.fromDate(this.now()),
      });
    });
    return { matchId: input.matchId, written: true };
  }

  async get(uid: string, matchId: string): Promise<MatchReviewResult> {
    const match = await this.db.doc(`matches/${matchId}`).get();
    const { partnerUid } = participants(match, uid);
    const [mine, theirs, partnerDeleted] = await Promise.all([
      this.db.doc(`reviews/${reviewId(matchId, uid)}`).get(),
      this.db.doc(`reviews/${reviewId(matchId, partnerUid)}`).get(),
      this.db.doc(`accountDeletions/${partnerUid}`).get(),
    ]);
    const myView = view(mine);
    // 내 후기가 없으면 상대 후기는 있든 없든 LOCKED 로만 알린다(존재 여부도 숨김).
    const received = !myView ? { visibility: "LOCKED" as const, review: null }
      : theirs.exists ? { visibility: "VISIBLE" as const, review: view(theirs) }
        : { visibility: "NOT_YET" as const, review: null };
    const num = (key: string) => (typeof match.get(key) === "number" ? (match.get(key) as number) : null);
    return {
      matchId,
      partnerUid,
      canWrite: !myView && eligible(match) && !partnerDeleted.exists,
      meetingDate: typeof match.get("meetingDate") === "string" ? match.get("meetingDate") : null,
      startMinute: num("startMinute"),
      endMinute: num("endMinute"),
      mine: myView,
      received,
    };
  }
}
