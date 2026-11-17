import { Timestamp, type Firestore, type Transaction } from "firebase-admin/firestore";
import { type CandidateResolver, type CreateInput, type RespondInput, key, requestError, validateSnapshot } from "./contracts";
import { CANCEL_COOLDOWN_MS, endsAt, overlaps, pendingCount, RECEIVED_LIMIT, reversePending, SENT_LIMIT } from "./policy";
import { readConflicts, writeConflicts, type ConflictPlan } from "./maintenance";

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
  terminalReason?: string;
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
      const senderGuard = await tx.get(this.db.doc(`requestUserGuards/${fromUid}`));
      const cooldown = senderGuard.get("cooldownUntil");
      if (cooldown instanceof Timestamp && cooldown.toMillis() > now.getTime()) {
        throw requestError("resource-exhausted", "REQUEST_COOLDOWN", "요청 취소 후 1분 동안은 새로 신청할 수 없어요.");
      }
      const s = validateSnapshot(fromUid, await this.candidates.resolve(tx, fromUid, input.candidateId, now), now);
      const recipientGuard = await tx.get(this.db.doc(`requestUserGuards/${s.toUid}`));
      const reverse = await tx.get(this.db.collection("matchRequestGuards").doc(key(s.toUid, fromUid)));
      if (reverse.exists) {
        const pending = await tx.get(this.db.doc(`matchRequests/${reverse.get("pendingRequestId")}`));
        if (pending.exists && pending.get("status") === "PENDING" && endsAt(pending.data() as RequestRecord) > now.getTime()) {
          throw requestError("already-exists", "REVERSE_PENDING", "이미 요청을 받은 상대예요. 받은 요청 탭에서 확인해주세요.");
        }
      }
      if (await reversePending(this.db, fromUid, s.toUid, now, tx)) {
        throw requestError("already-exists", "REVERSE_PENDING", "이미 요청을 받은 상대예요. 받은 요청 탭에서 확인해주세요.");
      }
      const guardRef = this.db.collection("matchRequestGuards").doc(key(fromUid, s.toUid));
      const guard = await tx.get(guardRef);
      if (guard.exists) {
        const pending = await tx.get(this.db.collection("matchRequests").doc(guard.get("pendingRequestId")));
        if (pending.exists && pending.get("status") === "PENDING" && endsAt(pending.data() as RequestRecord) > now.getTime()) {
          throw requestError("already-exists", "DUPLICATE_PENDING", "이미 보낸 요청이 있습니다.");
        }
      }
      const [sent, received] = await Promise.all([
        pendingCount(this.db, fromUid, "fromUid", now, SENT_LIMIT, tx),
        pendingCount(this.db, s.toUid, "toUid", now, RECEIVED_LIMIT, tx),
      ]);
      if (sent >= SENT_LIMIT) throw requestError("resource-exhausted", "SENT_LIMIT", "보낸 요청은 최대 3개까지 대기할 수 있어요.");
      if (received >= RECEIVED_LIMIT) throw requestError("resource-exhausted", "RECEIVED_LIMIT", "현재 신청할 수 없는 상대예요. 잠시 후 다시 확인해주세요.");
      const dayOfWeek = new Date(`${s.meetingDate}T00:00:00Z`).getUTCDay() || 7;
      const record: RequestRecord = { ...s, candidateId: input.candidateId, fromUid, dayOfWeek,
        overlapMinutes: s.endMinute - s.startMinute, status: "PENDING",
        createdAt: Timestamp.fromDate(now), updatedAt: Timestamp.fromDate(now) };
      tx.create(ref, record);
      tx.set(guardRef, { fromUid, toUid: s.toUid, pendingRequestId: requestId });
      this.touch(tx, fromUid, senderGuard.get("revision"));
      this.touch(tx, s.toUid, recipientGuard.get("revision"));
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
      const now = this.now();
      const guards = await Promise.all([record.fromUid, record.toUid].map(id => tx.get(this.db.doc(`requestUserGuards/${id}`))));
      const expired = endsAt(record) <= now.getTime();
      const canceled: RequestRecord = { ...record, status: "CANCELED", updatedAt: Timestamp.fromDate(now),
        terminalReason: expired ? "EXPIRED" : "MANUAL_CANCEL" };
      tx.update(ref, { status: canceled.status, updatedAt: canceled.updatedAt, terminalReason: canceled.terminalReason });
      this.touch(tx, record.fromUid, guards[0].get("revision"), expired ? {} : { cooldownUntil: Timestamp.fromMillis(now.getTime() + CANCEL_COOLDOWN_MS) });
      this.touch(tx, record.toUid, guards[1].get("revision"));
      return response(requestId, canceled);
    });
  }

  /** 동일 사용자의 생성·취소·수락이 다른 요청 문서를 변경해도 이 문서에서 경합한다. */
  private touch(tx: Transaction, uid: string, revision: unknown, extra: Record<string, unknown> = {}) {
    tx.set(this.db.doc(`requestUserGuards/${uid}`), {
      revision: (typeof revision === "number" && Number.isSafeInteger(revision) ? revision : 0) + 1, ...extra,
    }, { merge: true });
  }

  async respond(uid: string, input: RespondInput) {
    const ref = this.db.doc(`matchRequests/${input.requestId}`);
    return this.db.runTransaction(async tx => {
      const doc = await tx.get(ref);
      if (!doc.exists) throw requestError("not-found", "REQUEST_NOT_FOUND", "요청을 찾을 수 없습니다.");
      const record = doc.data() as RequestRecord;
      if (record.toUid !== uid) throw requestError("permission-denied", "NOT_RECIPIENT", "받은 요청만 수락하거나 거절할 수 있어요.");
      if (record.status !== "PENDING") {
        if (record.status === input.action) return response(input.requestId, record);
        throw requestError("failed-precondition", "REQUEST_NOT_PENDING", "이미 처리된 요청입니다.");
      }
      const now = this.now();
      const guards = await Promise.all([record.fromUid, record.toUid].map(id => tx.get(this.db.doc(`requestUserGuards/${id}`))));
      const expired = endsAt(record) <= now.getTime();
      let cleanup: ConflictPlan | undefined;
      if (!expired && input.action === "ACCEPTED") {
        validateSnapshot(record.fromUid, { toUid: record.toUid, purpose: record.purpose, meetingDate: record.meetingDate,
          startMinute: record.startMinute, endMinute: record.endMinute }, now);
        const [from, to, fromPublic, toPublic, ab, ba] = await Promise.all([
          tx.get(this.db.doc(`users/${record.fromUid}`)), tx.get(this.db.doc(`users/${record.toUid}`)),
          tx.get(this.db.doc(`publicProfiles/${record.fromUid}`)), tx.get(this.db.doc(`publicProfiles/${record.toUid}`)),
          tx.get(this.db.doc(`users/${record.fromUid}/blockedUsers/${record.toUid}`)),
          tx.get(this.db.doc(`users/${record.toUid}/blockedUsers/${record.fromUid}`)),
        ]);
        if (!from.exists || !to.exists || from.get("profileCompleted") !== true || to.get("profileCompleted") !== true
            || !fromPublic.exists || !toPublic.exists || ab.exists || ba.exists) {
          throw requestError("failed-precondition", "PARTICIPANT_UNAVAILABLE", "현재 매칭할 수 없는 상대예요.");
        }
        for (const participant of [record.fromUid, record.toUid]) {
          const matches = await tx.get(this.db.collection("matches").where("participantUids", "array-contains", participant)
            .where("meetingDate", "==", record.meetingDate));
          for (const match of matches.docs) {
            if (match.get("status") !== "ACTIVE") continue;
            const data = match.data() as RequestRecord;
            if (!Number.isInteger(data.startMinute) || !Number.isInteger(data.endMinute)
                || data.startMinute < 0 || data.startMinute >= data.endMinute || data.endMinute > 1440) {
              throw requestError("data-loss", "INVALID_MATCH", "확정 매칭 정보를 확인하지 못했어요.");
            }
            if (overlaps(record, data)) throw requestError("failed-precondition", "MATCH_CONFLICT", "이미 확정된 매칭과 시간이 겹쳐요.");
          }
        }
        const match = await tx.get(this.db.doc(`matches/${input.requestId}`));
        if (match.exists) throw requestError("failed-precondition", "MATCH_CONFLICT", "이미 처리된 매칭입니다.");
        cleanup = await readConflicts(this.db, tx, input.requestId, [record.fromUid, record.toUid], record, Timestamp.fromDate(now));
      }
      const next: RequestRecord = { ...record, status: expired ? "CANCELED" : input.action,
        updatedAt: Timestamp.fromDate(now), terminalReason: expired ? "EXPIRED" : input.action === "REJECTED" ? "RECIPIENT_REJECTED" : "ACCEPTED" };
      tx.update(ref, { status: next.status, updatedAt: next.updatedAt, terminalReason: next.terminalReason });
      if (next.status === "ACCEPTED") {
        tx.create(this.db.doc(`matches/${input.requestId}`), {
          requestId: input.requestId, participantUids: [record.fromUid, record.toUid], purpose: record.purpose,
          meetingDate: record.meetingDate, dayOfWeek: record.dayOfWeek, startMinute: record.startMinute,
          endMinute: record.endMinute, overlapMinutes: record.overlapMinutes, status: "ACTIVE", chatReady: false,
          createdAt: next.updatedAt, updatedAt: next.updatedAt,
        });
        tx.create(this.db.doc(`chatLinkJobs/${input.requestId}`), { matchId: input.requestId, status: "PENDING", updatedAt: next.updatedAt });
        writeConflicts(tx, cleanup!, [record.fromUid, record.toUid], record, now);
        tx.set(this.db.doc(`matchCleanupJobs/${input.requestId}`), { matchId: input.requestId,
          status: cleanup!.overflow ? "PENDING" : "DONE", attempts: 0, acceptedAt: next.updatedAt, updatedAt: next.updatedAt });
      }
      this.touch(tx, record.fromUid, guards[0].get("revision"));
      this.touch(tx, record.toUid, guards[1].get("revision"));
      return response(input.requestId, next);
    });
  }
}
