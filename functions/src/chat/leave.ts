import { randomUUID } from "node:crypto";
import type { Database } from "firebase-admin/database";
import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";

/**
 * BT-043 채팅방 나가기. 매칭 종료(BT-038)와 별개 이벤트다.
 *
 * - 방에 연결된 matches 중 ACTIVE 가 하나라도 있으면 거절한다(상단 대표 약속 하나만 보지 않는다).
 * - 잠금은 BT-038 계약을 그대로 쓴다(chatPairs/{pairId}): pendingMatchId(연결 중)·roomStateOwner/roomStateLeaseUntil(120초 lease)
 *   이 있으면 거절하고, 통과하면 같은 트랜잭션에서 stateRevision 을 올리고 lease 를 잡는다.
 * - 멤버 제거·나간 사람 목록 hidden·방 보관(control)·남은 사람 목록 archived 를 한 번의 RTDB update 로 적용한다(BT-034 §4).
 * - 메시지·안읽음은 건드리지 않는다. 메시지 최종 삭제는 신고 담당·SM 후속 합의 전에는 하지 않는다.
 * - 멱등: 이미 나갔으면 성공. RTDB 반영 뒤 Firestore 반영이 실패해도 다시 부르면 남은 부분을 마저 처리한다.
 */
export const LEAVE_LEASE_MILLIS = 120_000;
const KEY = /^[a-f0-9]{64}$/;

const failure = (reason: string, details: Record<string, unknown> = {}) =>
  new HttpsError("failed-precondition", "채팅방을 나가지 못했어요.", { reason, ...details });
const denied = () => new HttpsError("permission-denied", "이 채팅방을 나갈 수 없어요.");

export interface LeaveResult {
  chatRoomId: string;
  left: true;
}

interface Claim {
  pairId: string;
  pairUids: string[];
  remaining: string[];
}

export class ChatLeaveService {
  constructor(private readonly db: Firestore, private readonly rtdb: Database,
    private readonly now: () => Date = () => new Date()) {}

  async leave(roomId: string, uid: string): Promise<LeaveResult> {
    const owner = randomUUID();
    const linkRef = this.db.doc(`chatRoomLinks/${roomId}`);
    const claim = await this.db.runTransaction<Claim | null>(async tx => {
      const link = await tx.get(linkRef);
      if (!link.exists) throw denied();
      const pairId = link.get("pairId");
      const linked = link.get("participantUids");
      if (typeof pairId !== "string" || !KEY.test(pairId) || !Array.isArray(linked)) throw failure("INVALID_ROOM");
      const pairRef = this.db.doc(`chatPairs/${pairId}`);
      // 단일 필드 조건만 쓴다(복합 인덱스 불필요). 한 방의 약속 수는 적다.
      const [pair, matches] = await Promise.all([tx.get(pairRef), tx.get(this.db.collection("matches").where("chatRoomId", "==", roomId))]);
      const pairUids = pair.get("participantUids");
      if (!Array.isArray(pairUids) || pairUids.length !== 2 || pairUids.some(u => typeof u !== "string")) throw failure("INVALID_ROOM");
      if (!pairUids.includes(uid)) throw denied();
      // 이미 나간 사람(목록 참여자에서 빠짐)은 다시 부르면 성공으로 끝낸다.
      if (!linked.includes(uid)) return null;
      if (matches.docs.some(match => match.get("status") === "ACTIVE")) throw failure("ACTIVE_MATCH");
      const now = this.now().getTime();
      if (pair.get("pendingMatchId")) throw failure("RETRY_LATER", { retryAfterMillis: 10_000 });
      const lease = pair.get("roomStateLeaseUntil");
      if (lease instanceof Timestamp && lease.toMillis() > now) {
        throw failure("RETRY_LATER", { retryAfterMillis: lease.toMillis() - now });
      }
      tx.set(pairRef, { stateRevision: FieldValue.increment(1), roomStateOwner: owner,
        roomStateLeaseUntil: Timestamp.fromMillis(now + LEAVE_LEASE_MILLIS) }, { merge: true });
      return { pairId, pairUids: pairUids as string[], remaining: (linked as string[]).filter(u => u !== uid) };
    });
    if (!claim) return { chatRoomId: roomId, left: true };

    const pairRef = this.db.doc(`chatPairs/${claim.pairId}`);
    try {
      await this.applyRoom(roomId, uid, claim);
      await this.db.runTransaction(async tx => {
        const pair = await tx.get(pairRef);
        tx.set(linkRef, { participantUids: claim.remaining, archived: true, chatReady: false,
          updatedAt: Timestamp.fromDate(this.now()) }, { merge: true });
        if (pair.get("roomStateOwner") === owner) tx.set(pairRef, { roomStateOwner: null, roomStateLeaseUntil: null }, { merge: true });
      });
      return { chatRoomId: roomId, left: true };
    } catch (error) {
      // lease 를 바로 풀어 다시 시도할 수 있게 한다. 풀지 못해도 120초 뒤 만료된다.
      try {
        await this.db.runTransaction(async tx => {
          const pair = await tx.get(pairRef);
          if (pair.get("roomStateOwner") === owner) tx.set(pairRef, { roomStateOwner: null, roomStateLeaseUntil: null }, { merge: true });
        });
      } catch { /* lease expires */ }
      if (error instanceof HttpsError) throw error;
      throw failure("RETRY_LATER", { retryAfterMillis: 10_000 });
    }
  }

  /** 멤버 제거·hidden·보관을 한 번의 multi-path update 로. 이미 지워진 목록 항목은 부분 필드로 다시 만들지 않는다. */
  private async applyRoom(roomId: string, uid: string, claim: Claim) {
    const members = Object.keys((await this.rtdb.ref(`chatRooms/${roomId}/members`).get()).val() ?? {});
    // 제3자 멤버가 있는 잘못된 방은 바꾸지 않는다(link.ts 와 같은 기준).
    if (members.some(member => !claim.pairUids.includes(member))) throw failure("INVALID_ROOM");
    const patch: Record<string, unknown> = {
      [`chatRooms/${roomId}/members/${uid}`]: null,
      [`chatRooms/${roomId}/control`]: { archived: true, writable: false, chatReady: false },
    };
    for (const member of claim.pairUids) {
      const entry = (await this.rtdb.ref(`userChatRooms/${member}/${roomId}`).get()).val();
      if (entry === null) continue;
      patch[`userChatRooms/${member}/${roomId}/archived`] = true;
      patch[`userChatRooms/${member}/${roomId}/chatReady`] = false;
      patch[`userChatRooms/${member}/${roomId}/status`] = "ENDED";
      // 나간 사람(이번 호출자와 이미 나간 사람)만 목록에서 숨긴다. 남은 사람은 이전 대화로 계속 본다.
      if (!claim.remaining.includes(member)) patch[`userChatRooms/${member}/${roomId}/hidden`] = true;
    }
    await this.rtdb.ref().update(patch);
  }
}
