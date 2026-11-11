import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { type CandidateResolver, type CreateInput, key, requestError, validateSnapshot } from "./contracts";

export interface RequestRecord {
  candidateId: string;
  fromUid: string;
  toUid: string;
  purpose: string;
  meetingDate: string;
  dayOfWeek: number;
  startMinute: number;
  endMinute: number;
  overlapMinutes: number;
  status: "PENDING" | "ACCEPTED" | "REJECTED" | "CANCELED";
  createdAt: Timestamp;
  updatedAt: Timestamp;
}

export function response(id: string, record: RequestRecord) {
  const { candidateId: _candidateId, createdAt, updatedAt, ...snapshot } = record;
  return { id, ...snapshot, createdAtMillis: createdAt.toMillis(), updatedAtMillis: updatedAt.toMillis() };
}

export class RequestService {
  constructor(private readonly db: Firestore, private readonly candidates: CandidateResolver,
    private readonly now: () => Date = () => new Date()) {}

  async create(fromUid: string, input: CreateInput) {
    const requestId = key(fromUid, input.clientRequestId);
    const ref = this.db.collection("matchRequests").doc(requestId);
    return this.db.runTransaction(async (tx) => {
      const original = await tx.get(ref);
      if (original.exists) {
        const record = original.data() as RequestRecord;
        if (record.fromUid !== fromUid || record.candidateId !== input.candidateId) {
          throw requestError("already-exists", "IDEMPOTENCY_CONFLICT", "다른 요청에 사용한 식별자입니다.");
        }
        // 재시도는 현재 시간표를 읽지 않고 생성 당시 스냅샷을 그대로 반환한다.
        return response(requestId, record);
      }
      const now = this.now();
      const s = validateSnapshot(fromUid, await this.candidates.resolve(tx, fromUid, input.candidateId, now), now);
      const guardRef = this.db.collection("matchRequestGuards").doc(key(fromUid, s.toUid));
      const guard = await tx.get(guardRef);
      if (guard.exists) {
        const pending = await tx.get(this.db.collection("matchRequests").doc(guard.get("pendingRequestId")));
        if (pending.exists && pending.get("status") === "PENDING") {
          throw requestError("already-exists", "DUPLICATE_PENDING", "이미 보낸 요청이 있습니다.");
        }
      }
      const dayOfWeek = new Date(`${s.meetingDate}T00:00:00Z`).getUTCDay() || 7;
      const record: RequestRecord = { ...s, candidateId: input.candidateId, fromUid, dayOfWeek,
        overlapMinutes: s.endMinute - s.startMinute, status: "PENDING",
        createdAt: Timestamp.fromDate(now), updatedAt: Timestamp.fromDate(now) };
      tx.create(ref, record);
      tx.set(guardRef, { fromUid, toUid: s.toUid, pendingRequestId: requestId });
      return response(requestId, record);
    });
  }

  async cancel(uid: string, requestId: string) {
    const ref = this.db.collection("matchRequests").doc(requestId);
    return this.db.runTransaction(async (tx) => {
      const document = await tx.get(ref);
      if (!document.exists) throw requestError("not-found", "REQUEST_NOT_FOUND", "요청을 찾을 수 없습니다.");
      const record = document.data() as RequestRecord;
      if (record.fromUid !== uid) throw requestError("permission-denied", "NOT_SENDER", "보낸 요청만 취소할 수 있습니다.");
      if (record.status === "CANCELED") return response(requestId, record);
      if (record.status !== "PENDING") throw requestError("failed-precondition", "REQUEST_NOT_PENDING", "이미 처리된 요청입니다.");
      const canceled: RequestRecord = { ...record, status: "CANCELED", updatedAt: Timestamp.fromDate(this.now()) };
      tx.update(ref, { status: canceled.status, updatedAt: canceled.updatedAt });
      return response(requestId, canceled);
    });
  }
}
