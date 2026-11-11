import { createHash } from "node:crypto";
import type { Transaction } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { z } from "zod";

export const createInput = z.object({
  candidateId: z.string().min(1).max(200).regex(/^[A-Za-z0-9:_-]+$/),
  clientRequestId: z.uuid(),
}).strict();

const date = z.iso.date();
/** 클라이언트 입력이 아니다. BT-017 서버 적격성 검사기가 반환할 최소 스냅샷. */
export const candidateSnapshot = z.object({
  toUid: z.string().min(1).max(128),
  purpose: z.string().trim().min(1).max(80),
  meetingDate: date,
  startMinute: z.number().int().min(0).max(1439),
  endMinute: z.number().int().min(1).max(1440),
}).strict().refine((s) => s.endMinute - s.startMinute >= 60);

export type CandidateSnapshot = z.infer<typeof candidateSnapshot>;
export type CreateInput = z.infer<typeof createInput>;
/** BT-017은 동일 트랜잭션에서 양측 ON/최소시간/목적/차단/충돌/revision을 재검증해야 한다. */
export interface CandidateResolver {
  resolve(tx: Transaction, fromUid: string, candidateId: string, now: Date): Promise<CandidateSnapshot>;
}

export const disconnectedCandidates: CandidateResolver = {
  async resolve() {
    throw requestError("failed-precondition", "SEARCH_NOT_CONNECTED", "실제 매칭 검색 연결이 필요합니다.");
  },
};

export function requestError(code: ConstructorParameters<typeof HttpsError>[0], reason: string, message: string): HttpsError {
  return new HttpsError(code, message, { reason, retryable: false });
}

export function key(...parts: string[]): string {
  return createHash("sha256").update(JSON.stringify(parts)).digest("hex");
}

export function validateSnapshot(fromUid: string, raw: CandidateSnapshot, now: Date): CandidateSnapshot {
  const parsed = candidateSnapshot.safeParse(raw);
  if (!parsed.success) throw requestError("failed-precondition", "INVALID_CANDIDATE", "후보 정보를 다시 확인해주세요.");
  const s = parsed.data;
  if (s.toUid === fromUid) throw requestError("invalid-argument", "SELF_REQUEST", "자신에게 요청할 수 없습니다.");
  const end = Date.parse(`${s.meetingDate}T00:00:00+09:00`) + s.endMinute * 60_000;
  if (end <= now.getTime()) throw requestError("failed-precondition", "CANDIDATE_EXPIRED", "공강시간이 종료되었습니다.");
  return s;
}
