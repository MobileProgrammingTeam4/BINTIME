import { randomUUID } from "node:crypto";
import type { Database } from "firebase-admin/database";
import { Timestamp, type Firestore, type Transaction } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { chatPairKey } from "./pair";
import { RoomStateService } from "./roomState";
export { chatPairKey } from "./pair";

/** BT-043도 같은 pair 문서에서 pendingMatchId와 미종료 약속을 확인해야 한다. */
const failure = (reason: string, details: Record<string, unknown> = {}) => new HttpsError("failed-precondition", "채팅방을 준비하지 못했어요. 잠시 후 다시 시도해주세요.", { reason, ...details });

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
      // BT-045(#130 리뷰 D): 탈퇴를 시작한 참여자가 있으면 새로 연결하지 않는다. 탈퇴 정리가 끝난 뒤 늦게 도는 연결이
      // 그 UID 의 멤버·목록을 다시 만들지 않게 한다. accountDeletions 는 탈퇴 첫 단계에서 쓰고 지우지 않는다.
      await this.assertNoDeletion(tx, participants);
      const owner = pair.get("pendingMatchId");
      if (owner && owner !== matchId) throw failure("CHAT_PAIR_BUSY", { blockingMatchId: owner });
      const stateLease = pair.get("roomStateLeaseUntil");
      if (stateLease instanceof Timestamp && stateLease.toMillis() > this.now().getTime()) {
        throw failure("CHAT_RETRY_LATER", { retryAfterMillis: stateLease.toMillis() - this.now().getTime() });
      }
      const lease = job.get("leaseUntil");
      if (lease instanceof Timestamp && lease.toMillis() > this.now().getTime()) throw failure("CHAT_LINK_BUSY", { retryAfterMillis: lease.toMillis() - this.now().getTime() });
      const nextAttempt = job.get("nextAttemptAt");
      if (nextAttempt instanceof Timestamp && nextAttempt.toMillis() > this.now().getTime()) throw failure("CHAT_RETRY_LATER", { retryAfterMillis: nextAttempt.toMillis() - this.now().getTime() });
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
      // 기존 control 없는 방은 Rules 호환을 유지한다. 새 방은 쓰기 차단을 먼저 저장한 뒤 멤버 권한을 부여한다.
      const controlResult = await this.rtdb.ref(`chatRooms/${roomId}/control`).transaction(current => {
        if (current?.archived === true) return undefined;
        return current ?? { chatReady: false, writable: false, archived: false };
      });
      if (!controlResult.committed) throw failure("ROOM_ARCHIVED");
      const membersRef = this.rtdb.ref(`chatRooms/${roomId}/members`);
      const membersResult = await membersRef.transaction(current => {
        if (current === null) return Object.fromEntries(participants.map(u => [u, { joinedAt: this.now().getTime() }]));
        const keys = Object.keys(current);
        if (keys.length !== 2 || !participants.every(u => keys.includes(u))) return undefined;
        return current;
      });
      if (!membersResult.committed) throw failure("ROOM_MEMBERS_CHANGED");
      for (const uid of participants) {
        const otherUid = participants.find(u => u !== uid)!;
        const entryResult = await this.rtdb.ref(`userChatRooms/${uid}/${roomId}`).transaction(current => {
          // linkedAt(BT-034): 항목을 처음 만들 때 한 번만 함수 서버 시각으로 기록한다. 메시지 없는 새 방의 목록 정렬 기준이다.
          // 재시도·기존 방 재사용은 아래 기존 항목 분기라 바꾸지 않는다.
          if (current === null) return { otherUid, status: "ACTIVE", hidden: false, unreadCount: 0, lastMessage: "", lastMessageAt: 0,
            linkedAt: this.now().getTime(), chatReady: false, archived: false };
          if (current.otherUid !== otherUid || current.hidden === true || current.archived === true) return undefined;
          return current; // 안읽음·최근 메시지·중복 처리 이력은 절대 초기화하지 않는다.
        });
        if (!entryResult.committed) throw failure("ROOM_ENTRY_CHANGED");
      }
      // Mirror the current aggregate before marking the room ready. Reused ENDED rooms
      // must become ACTIVE even if the final sync is unavailable. Include this match,
      // which has not yet received its chatRoomId, and never revive an already-ended one.
      const [linkingMatch, active] = await Promise.all([
        matchRef.get(), this.db.collection("matches").where("chatRoomId", "==", roomId).where("status", "==", "ACTIVE").limit(1).get(),
      ]);
      const status = linkingMatch.get("status") === "ACTIVE" || !active.empty ? "ACTIVE" : "ENDED";
      const patch: Record<string, unknown> = { [`chatRooms/${roomId}/control/chatReady`]: true,
        [`chatRooms/${roomId}/control/writable`]: true };
      for (const uid of participants) {
        patch[`userChatRooms/${uid}/${roomId}/chatReady`] = true;
        patch[`userChatRooms/${uid}/${roomId}/status`] = status;
      }
      await this.rtdb.ref().update(patch);
      await this.db.runTransaction(async tx => {
        const pairRef = this.db.doc(`chatPairs/${pairId}`);
        const [pair, job] = await Promise.all([tx.get(pairRef), tx.get(jobRef)]);
        if (job.get("status") === "READY") return;
        // 연결 도중 탈퇴가 시작됐으면 완료로 표시하지 않는다. 이미 쓴 RTDB 항목은 탈퇴가 다시 실행될 때 정리한다
        // (탈퇴는 lease 가 남은 연결이 있으면 끝날 때까지 기다리게 한다, account/deleteAccount.ts).
        await this.assertNoDeletion(tx, participants);
        if (pair.get("pendingMatchId") !== matchId || job.get("plan.roomId") !== roomId || job.get("workerId") !== workerId) throw failure("STALE_LINK");
        const updatedAt = Timestamp.fromDate(this.now());
        tx.set(this.db.doc(`chatRoomLinks/${roomId}`), { participantUids: participants, pairId, chatReady: true, archived: false, updatedAt });
        tx.update(matchRef, { chatRoomId: roomId, chatReady: true, updatedAt });
        tx.set(jobRef, { status: "READY", roomId, workerId: null, leaseUntil: null, nextAttemptAt: null, updatedAt }, { merge: true });
        tx.set(pairRef, { roomId, pendingMatchId: null }, { merge: true });
        tx.set(this.db.doc(`roomStateJobs/${roomId}`), { roomId, participantUids: participants,
          status: "PENDING", nextAttemptAt: updatedAt }, { merge: true });
      });
      // A match may have ended while its chat was being prepared. Aggregate current matches, not a hardcoded ACTIVE.
      try { await new RoomStateService(this.db, this.rtdb, this.now).sync(roomId, participants); } catch { /* scheduler repairs */ }
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

  private async assertNoDeletion(tx: Transaction, participants: string[]) {
    const deletions = await Promise.all(participants.map(u => tx.get(this.db.doc(`accountDeletions/${u}`))));
    if (deletions.some(doc => doc.exists)) throw failure("PARTICIPANT_DELETED");
  }

  private async archive(roomId: string, participants: string[]) {
    const members = (await this.rtdb.ref(`chatRooms/${roomId}/members`).get()).val() ?? {};
    const remaining = Object.keys(members);
    if (remaining.some(u => !participants.includes(u)) || remaining.length > 1) throw failure("ROOM_MEMBERS_CHANGED");
    // 양측 이탈 기록도 서버에만 보관한다. 신고 보존 기간·최종 삭제는 별도 합의하며 여기서 메시지를 지우지 않는다.
    const patch: Record<string, unknown> = { [`chatRooms/${roomId}/control`]: { archived: true, writable: false, chatReady: false } };
    for (const uid of participants) {
      const entry = (await this.rtdb.ref(`userChatRooms/${uid}/${roomId}`).get()).val();
      if (entry === null) continue; // 이미 지워진 사용자 목록을 부분 필드만으로 재생성하지 않는다.
      if (entry.otherUid !== participants.find(u => u !== uid)) throw failure("INVALID_ROOM");
      patch[`userChatRooms/${uid}/${roomId}/archived`] = true;
      patch[`userChatRooms/${uid}/${roomId}/chatReady`] = false;
      patch[`userChatRooms/${uid}/${roomId}/status`] = "ENDED";
      if (!remaining.includes(uid)) patch[`userChatRooms/${uid}/${roomId}/hidden`] = true;
    }
    await this.rtdb.ref().update(patch);
    await this.db.doc(`chatRoomLinks/${roomId}`).set({ participantUids: remaining, archived: true, chatReady: false,
      updatedAt: Timestamp.fromDate(this.now()) }, { merge: true });
  }
}
