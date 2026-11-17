import type { Firestore, Query, Transaction } from "firebase-admin/firestore";
import { requestError } from "./contracts";

export const SENT_LIMIT = 3;
export const RECEIVED_LIMIT = 5;
export const CANCEL_COOLDOWN_MS = 60_000;

export interface RequestTime { meetingDate: string; startMinute: number; endMinute: number }

export function endsAt(s: Pick<RequestTime, "meetingDate" | "endMinute">): number {
  const midnight = Date.parse(`${s.meetingDate}T00:00:00+09:00`);
  if (!Number.isFinite(midnight) || !Number.isInteger(s.endMinute) || s.endMinute < 1 || s.endMinute > 1440
      || new Date(midnight + 9 * 3_600_000).toISOString().slice(0, 10) !== s.meetingDate) {
    throw requestError("data-loss", "INVALID_REQUEST", "요청 정보를 확인하지 못했어요.");
  }
  return midnight + s.endMinute * 60_000;
}

export function overlaps(a: RequestTime, b: RequestTime): boolean {
  return a.meetingDate === b.meetingDate && a.startMinute < b.endMinute && b.startMinute < a.endMinute;
}

/** 오늘의 미종료·미래 날짜를 따로 제한 조회한다. 과거/만료 요청이 limit을 소모하지 않는다. */
export function liveQueries(base: Query, now: Date, limit: number): Query[] {
  const seoul = new Date(now.getTime() + 9 * 3_600_000);
  const date = seoul.toISOString().slice(0, 10);
  const minute = seoul.getUTCHours() * 60 + seoul.getUTCMinutes() + seoul.getUTCSeconds() / 60 + seoul.getUTCMilliseconds() / 60_000;
  return [base.where("meetingDate", "==", date).where("endMinute", ">", minute).limit(limit),
    base.where("meetingDate", ">", date).limit(limit)];
}

export async function pendingCount(db: Firestore, uid: string, direction: "fromUid" | "toUid", now: Date,
  limit: number, tx?: Transaction): Promise<number> {
  const queries = liveQueries(db.collection("matchRequests").where(direction, "==", uid).where("status", "==", "PENDING"), now, limit);
  const snapshots = await Promise.all(queries.map(q => tx ? tx.get(q) : q.get()));
  return Math.min(limit, snapshots.reduce((sum, s) => sum + s.size, 0));
}

/** 검색용 읽기. 생성 API는 같은 조건을 사용자 잠금과 함께 트랜잭션 안에서 다시 판정한다. */
export async function canReceive(db: Firestore, fromUid: string, toUid: string, now: Date): Promise<boolean> {
  if (await pendingCount(db, toUid, "toUid", now, RECEIVED_LIMIT) >= RECEIVED_LIMIT) return false;
  return !(await reversePending(db, fromUid, toUid, now));
}

export async function reversePending(db: Firestore, fromUid: string, toUid: string, now: Date, tx?: Transaction): Promise<boolean> {
  const base = db.collection("matchRequests").where("fromUid", "==", toUid)
    .where("toUid", "==", fromUid).where("status", "==", "PENDING");
  const reverse = await Promise.all(liveQueries(base, now, 1).map(q => tx ? tx.get(q) : q.get()));
  return reverse.some(s => !s.empty);
}
