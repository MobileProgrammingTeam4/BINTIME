import { createHash, randomUUID } from "node:crypto";
import type { Database } from "firebase-admin/database";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";

/** BT-043도 같은 pair 문서에서 pendingMatchId와 미종료 약속을 확인해야 한다. */
export const chatPairKey = (uids: readonly string[]) => createHash("sha256").update(JSON.stringify([...uids].sort())).digest("hex");
const failure = (reason: string) => new HttpsError("failed-precondition", "채팅방을 준비하지 못했어요. 잠시 후 다시 시도해주세요.", { reason });

interface Plan { roomId: string; previousRoomId: string | null; archive: boolean }
export interface ChatLinkResult { matchId: string; chatRoomId: string | null; chatReady: boolean }

/** Firestore 잠금 → 영속 계획 → RTDB 준비 → Firestore 완료. 트랜잭션 안에서 RTDB를 호출하지 않는다. */
export class ChatLinkService {
  constructor(private readonly db: Firestore, private readonly rtdb: Database,
    private readonly now: () => Date = () => new Date()) {}

  async link(matchId: string, uid?: string): Promise<ChatLinkResult> {
    const workerId = randomUUID();
    const matchRef = this.db.doc(`matches/${matchId}`), jobRef = this.db.doc(`chatLinkJobs/${matchId}`);
    const claim = await this.db.runTransaction(async tx => {
      const match = await tx.get(matchRef);
      if (!match.exists) throw new HttpsError("not-found", "매칭을 찾을 수 없어요.");
      const uids = match.get("participantUids") as unknown;
      if (!Array.isArray(uids) || uids.length !== 2 || new Set(uids).size !== 2 || uids.some(u => typeof u !== "string" || !u || /[.#$\[\]/]/.test(u))) throw failure("INVALID_MATCH");
      const participants = uids as string[];
      if (uid !== undefined && !participants.includes(uid)) throw new HttpsError("permission-denied", "매칭 참여자만 채팅을 준비할 수 있어요.");
      const pairId = chatPairKey(participants), pairRef = this.db.doc(`chatPairs/${pairId}`);
      const [pair, job] = await Promise.all([tx.get(pairRef), tx.get(jobRef)]);
      if (job.get("status") === "READY") {
        const roomId = job.get("roomId") as string;
        const room = await tx.get(this.db.doc(`chatRoomLinks/${roomId}`));
        return { participants, pairId, ready: { matchId, chatRoomId: roomId, chatReady: room.get("chatReady") === true }, plan: null };
      }
      const owner = pair.get("pendingMatchId");
      if (owner && owner !== matchId) throw failure("CHAT_PAIR_BUSY");
      const lease = job.get("leaseUntil");
      if (lease instanceof Timestamp && lease.toMillis() > this.now().getTime()) throw failure("CHAT_LINK_BUSY");
      const nextAttempt = job.get("nextAttemptAt");
      if (nextAttempt instanceof Timestamp && nextAttempt.toMillis() > this.now().getTime()) throw failure("CHAT_RETRY_LATER");
      tx.set(pairRef, { participantUids: participants, pendingMatchId: matchId }, { merge: true });
      // Callable/trigger 최대 실행 60초보다 긴 120초 lease. 프로세스 종료 후 같은 계획으로 복구한다.
      tx.set(jobRef, { matchId, pairId, status: "PREPARING", workerId,
        leaseUntil: Timestamp.fromMillis(this.now().getTime() + 120_000), updatedAt: Timestamp.fromDate(this.now()) }, { merge: true });
      return { participants, pairId, ready: null, plan: job.get("plan") as Plan | undefined,
        previousRoomId: pair.get("roomId") as string | undefined };
    });
    if (claim.ready) return claim.ready;
    const { participants, pairId } = claim;
    try {
      let plan = claim.plan;
      if (!plan) {
        const previousRoomId = claim.previousRoomId ?? null;
        const old = previousRoomId ? (await this.rtdb.ref(`chatRooms/${previousRoomId}/members`).get()).val() : null;
        const members = old && typeof old === "object" ? Object.keys(old) : [];
        // 제3자 멤버를 가진 잘못된 방을 재사용하거나 지우지 않는다.
        if (members.some(u => !participants.includes(u))) throw failure("INVALID_ROOM");
        const control = previousRoomId ? (await this.rtdb.ref(`chatRooms/${previousRoomId}/control`).get()).val() : null;
        const reuse = members.length === 2 && control?.archived !== true;
        const proposal: Plan = { roomId: reuse ? previousRoomId! : matchId, previousRoomId, archive: !!previousRoomId && !reuse };
        plan = await this.db.runTransaction(async tx => {
          const [job, pair] = await Promise.all([tx.get(jobRef), tx.get(this.db.doc(`chatPairs/${pairId}`))]);
          if (pair.get("pendingMatchId") !== matchId || job.get("workerId") !== workerId) throw failure("STALE_LINK");
          if (job.get("plan")) return job.get("plan") as Plan;
          tx.set(jobRef, { plan: proposal }, { merge: true });
          return proposal;
        });
      }
      if (plan.archive && plan.previousRoomId) await this.archive(plan.previousRoomId, participants);
      const roomId = plan.roomId;
      const membersRef = this.rtdb.ref(`chatRooms/${roomId}/members`);
      const membersResult = await membersRef.transaction(current => {
        if (current === null) return Object.fromEntries(participants.map(u => [u, { joinedAt: this.now().getTime() }]));
        const keys = Object.keys(current);
        if (keys.length !== 2 || !participants.every(u => keys.includes(u))) return undefined;
        return current;
      });
      if (!membersResult.committed) throw failure("ROOM_MEMBERS_CHANGED");
      const controlResult = await this.rtdb.ref(`chatRooms/${roomId}/control`).transaction(current => {
        if (current?.archived === true) return undefined;
        return current ?? { chatReady: false, writable: false, archived: false };
      });
      if (!controlResult.committed) throw failure("ROOM_ARCHIVED");
      for (const uid of participants) {
        const otherUid = participants.find(u => u !== uid)!;
        const entryResult = await this.rtdb.ref(`userChatRooms/${uid}/${roomId}`).transaction(current => {
          if (current === null) return { otherUid, status: "ACTIVE", hidden: false, unreadCount: 0, lastMessage: "", lastMessageAt: 0, chatReady: false, archived: false };
          if (current.otherUid !== otherUid || current.hidden === true || current.archived === true) return undefined;
          return current; // 안읽음·최근 메시지·중복 처리 이력은 절대 초기화하지 않는다.
        });
        if (!entryResult.committed) throw failure("ROOM_ENTRY_CHANGED");
      }
      const patch: Record<string, unknown> = { [`chatRooms/${roomId}/control/chatReady`]: true,
        [`chatRooms/${roomId}/control/writable`]: true };
      for (const uid of participants) {
        patch[`userChatRooms/${uid}/${roomId}/chatReady`] = true;
        patch[`userChatRooms/${uid}/${roomId}/status`] = "ACTIVE";
      }
      await this.rtdb.ref().update(patch);
      await this.db.runTransaction(async tx => {
        const pairRef = this.db.doc(`chatPairs/${pairId}`);
        const [pair, job] = await Promise.all([tx.get(pairRef), tx.get(jobRef)]);
        if (job.get("status") === "READY") return;
        if (pair.get("pendingMatchId") !== matchId || job.get("plan.roomId") !== roomId || job.get("workerId") !== workerId) throw failure("STALE_LINK");
        const updatedAt = Timestamp.fromDate(this.now());
        tx.set(this.db.doc(`chatRoomLinks/${roomId}`), { participantUids: participants, pairId, chatReady: true, archived: false, updatedAt });
        tx.update(matchRef, { chatRoomId: roomId, chatReady: true, updatedAt });
        tx.set(jobRef, { status: "READY", roomId, workerId: null, leaseUntil: null, nextAttemptAt: null, updatedAt }, { merge: true });
        tx.set(pairRef, { roomId, pendingMatchId: null }, { merge: true });
      });
      return { matchId, chatRoomId: roomId, chatReady: true };
    } catch (error) {
      await this.db.runTransaction(async tx => {
        const job = await tx.get(jobRef);
        if (job.get("status") === "READY" || job.get("workerId") !== workerId) return;
        tx.set(jobRef, { status: "FAILED", nextAttemptAt: Timestamp.fromMillis(this.now().getTime() + 10_000),
          workerId: null, leaseUntil: null, updatedAt: Timestamp.fromDate(this.now()) }, { merge: true });
      });
      throw error;
    }
  }

  private async archive(roomId: string, participants: string[]) {
    const members = (await this.rtdb.ref(`chatRooms/${roomId}/members`).get()).val() ?? {};
    const remaining = Object.keys(members);
    if (remaining.some(u => !participants.includes(u)) || remaining.length > 1) throw failure("ROOM_MEMBERS_CHANGED");
    const patch: Record<string, unknown> = {};
    if (!remaining.length) patch[`chatRooms/${roomId}`] = null;
    else {
      patch[`chatRooms/${roomId}/control`] = { archived: true, writable: false, chatReady: false };
      for (const uid of remaining) {
        patch[`userChatRooms/${uid}/${roomId}/archived`] = true;
        patch[`userChatRooms/${uid}/${roomId}/chatReady`] = false;
        patch[`userChatRooms/${uid}/${roomId}/status`] = "ENDED";
      }
    }
    await this.rtdb.ref().update(patch);
    await this.db.doc(`chatRoomLinks/${roomId}`).set({ participantUids: remaining, archived: true, chatReady: false,
      updatedAt: Timestamp.fromDate(this.now()) }, { merge: true });
  }
}
