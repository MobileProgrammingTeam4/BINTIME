import { randomUUID } from "node:crypto";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import type { Database } from "firebase-admin/database";
import { chatPairKey } from "./pair";

/** Durable mirror of match aggregation. Never changes members, messages, hidden or write permission.
 * BT-043 must share roomStateLeaseUntil and pendingMatchId before changing room membership.
 */
export class RoomStateService {
  constructor(private readonly db: Firestore, private readonly rtdb: Database,
    private readonly now: () => Date = () => new Date()) {}

  async sync(roomId: string, participants: string[]): Promise<boolean> {
    const owner = randomUUID(), pairRef = this.db.doc(`chatPairs/${chatPairKey(participants)}`);
    const jobRef = this.db.doc(`roomStateJobs/${roomId}`), linkRef = this.db.doc(`chatRoomLinks/${roomId}`);
    const claim = await this.db.runTransaction(async tx => {
      const [pair, job, link, active] = await Promise.all([tx.get(pairRef), tx.get(jobRef), tx.get(linkRef),
        tx.get(this.db.collection("matches").where("chatRoomId", "==", roomId).where("status", "==", "ACTIVE").limit(1))]);
      const now = this.now(), timestamp = Timestamp.fromDate(now), lease = pair.get("roomStateLeaseUntil");
      if (pair.get("pendingMatchId") || (lease instanceof Timestamp && lease.toMillis() > now.getTime())) {
        tx.set(jobRef, { roomId, participantUids: participants, status: "PENDING", nextAttemptAt: Timestamp.fromMillis(now.getTime() + 10_000) }, { merge: true });
        return null;
      }
      // Late events must not resurrect a previous room or a room already left by either participant.
      if (pair.get("roomId") !== roomId || link.get("archived") === true || !link.exists) {
        tx.set(jobRef, { status: "DONE", nextAttemptAt: null, updatedAt: timestamp }, { merge: true });
        return null;
      }
      const generation = Number(job.get("generation") || 0);
      tx.set(pairRef, { roomStateOwner: owner, roomStateLeaseUntil: Timestamp.fromMillis(now.getTime() + 120_000) }, { merge: true });
      tx.set(jobRef, { roomId, participantUids: participants, status: "RUNNING", generation,
        nextAttemptAt: Timestamp.fromMillis(now.getTime() + 120_000), updatedAt: timestamp }, { merge: true });
      return { generation, status: active.empty ? "ENDED" : "ACTIVE" };
    });
    if (!claim) return false;
    try {
      const patch: Record<string, unknown> = {};
      for (const uid of participants) {
        const entry = (await this.rtdb.ref(`userChatRooms/${uid}/${roomId}`).get()).val();
        if (entry && entry.hidden !== true && entry.archived !== true) patch[`userChatRooms/${uid}/${roomId}/status`] = claim.status;
      }
      if (Object.keys(patch).length) await this.rtdb.ref().update(patch);
      await this.db.runTransaction(async tx => {
        const [pair, job] = await Promise.all([tx.get(pairRef), tx.get(jobRef)]);
        if (pair.get("roomStateOwner") !== owner) return;
        const dirty = Number(job.get("generation") || 0) !== claim.generation;
        tx.set(jobRef, { status: dirty ? "PENDING" : "DONE", nextAttemptAt: dirty ? Timestamp.fromDate(this.now()) : null,
          updatedAt: Timestamp.fromDate(this.now()) }, { merge: true });
        tx.set(pairRef, { roomStateOwner: null, roomStateLeaseUntil: null }, { merge: true });
      });
      return true;
    } catch (error) {
      await this.db.runTransaction(async tx => {
        const pair = await tx.get(pairRef);
        if (pair.get("roomStateOwner") !== owner) return;
        tx.set(pairRef, { roomStateOwner: null, roomStateLeaseUntil: null }, { merge: true });
        tx.set(jobRef, { status: "PENDING", nextAttemptAt: Timestamp.fromMillis(this.now().getTime() + 10_000) }, { merge: true });
      });
      throw error;
    }
  }

  async retryBatch(): Promise<void> {
    const jobs = await this.db.collection("roomStateJobs").where("status", "in", ["PENDING", "RUNNING"])
      .where("nextAttemptAt", "<=", Timestamp.fromDate(this.now())).limit(20).get();
    for (const job of jobs.docs) {
      try { await this.sync(job.id, job.get("participantUids")); } catch { /* durable job remains retryable */ }
    }
  }
}
