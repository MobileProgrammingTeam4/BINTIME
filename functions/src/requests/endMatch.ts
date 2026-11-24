import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { endsAt } from "./policy";
import { chatPairKey } from "../chat/pair";
import type { RoomStateService } from "../chat/roomState";

export interface EndSnapshot { meetingDate: string; startMinute: number; endMinute: number }
export function endDecision(snapshot: EndSnapshot, now: Date, manual: boolean) {
  const end = endsAt(snapshot), start = Date.parse(`${snapshot.meetingDate}T00:00:00+09:00`) + snapshot.startMinute * 60_000;
  if (!Number.isInteger(snapshot.startMinute) || snapshot.startMinute < 0 || start >= end) throw new HttpsError("data-loss", "매칭 시간 정보가 올바르지 않아요.");
  if (!manual && now.getTime() < end) return null;
  const automatic = now.getTime() >= end;
  return { endedAt: Timestamp.fromMillis(automatic ? end : now.getTime()), endReason: automatic ? "AUTO" : "MANUAL",
    reviewEligible: automatic || now.getTime() >= start };
}

export class MatchEndService {
  constructor(private readonly db: Firestore, private readonly rooms: RoomStateService,
    private readonly now: () => Date = () => new Date()) {}

  async end(matchId: string, uid?: string) {
    const result = await this.db.runTransaction(async tx => {
      const ref = this.db.doc(`matches/${matchId}`), match = await tx.get(ref);
      if (!match.exists) throw new HttpsError("not-found", "매칭을 찾을 수 없어요.");
      const participants = match.get("participantUids") as string[];
      if (!Array.isArray(participants) || participants.length !== 2 || new Set(participants).size !== 2
          || participants.some(u => typeof u !== "string" || !u || /[.#$\[\]/]/.test(u))) throw new HttpsError("data-loss", "매칭 참여 정보가 올바르지 않아요.");
      if (uid !== undefined && !participants.includes(uid)) throw new HttpsError("permission-denied", "매칭 참여자만 종료할 수 있어요.");
      if (!["ACTIVE", "ENDED"].includes(match.get("status"))) throw new HttpsError("data-loss", "매칭 상태를 확인할 수 없어요.");
      const now = this.now(), roomId = match.get("chatRoomId") as string | undefined;
      const decision = match.get("status") === "ENDED" ? null : endDecision(match.data() as EndSnapshot, now, uid !== undefined);
      if (decision) {
        // Shared serialization point with link and BT-043 leave. Original time/request snapshots stay immutable.
        tx.set(this.db.doc(`chatPairs/${chatPairKey(participants)}`), { stateRevision: FieldValue.increment(1) }, { merge: true });
        tx.update(ref, { status: "ENDED", ...decision, endedBy: decision.endReason === "MANUAL" ? uid : null,
          endProcessedAt: Timestamp.fromDate(now), updatedAt: Timestamp.fromDate(now) });
        if (roomId) tx.set(this.db.doc(`roomStateJobs/${roomId}`), { roomId, participantUids: participants,
          generation: FieldValue.increment(1), status: "PENDING", nextAttemptAt: Timestamp.fromDate(now) }, { merge: true });
      }
      return { matchId, status: decision ? "ENDED" : match.get("status"), chatRoomId: roomId ?? null, participants };
    });
    // State transition succeeds even if RTDB is temporarily unavailable. Scheduler repairs the persistent job.
    if (result.status === "ENDED" && result.chatRoomId) {
      try { await this.rooms.sync(result.chatRoomId, result.participants); } catch { /* repair queued */ }
    }
    return { matchId: result.matchId, status: result.status };
  }

  async runBatch(): Promise<{ ended: number; failed: number }> {
    const seoul = new Date(this.now().getTime() + 9 * 3_600_000), date = seoul.toISOString().slice(0, 10);
    const minute = seoul.getUTCHours() * 60 + seoul.getUTCMinutes();
    const base = this.db.collection("matches").where("status", "==", "ACTIVE");
    const [past, today] = await Promise.all([base.where("meetingDate", "<", date).limit(25).get(),
      base.where("meetingDate", "==", date).where("endMinute", "<=", minute).limit(25).get()]);
    let ended = 0, failed = 0;
    for (const match of [...past.docs, ...today.docs]) { try { if ((await this.end(match.id)).status === "ENDED") ended++; } catch { failed++; } }
    await this.rooms.retryBatch();
    return { ended, failed };
  }
}
