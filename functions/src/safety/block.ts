import type { Database } from "firebase-admin/database";
import { FieldValue, Timestamp, type Firestore, type QueryDocumentSnapshot } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { z } from "zod";
import { chatPairKey } from "../chat/link";

/**
 * B · BT-046 사용자 차단·차단 해제 (docs/BT-046.md).
 *
 * - 차단 기록: users/{uid}/blockedUsers/{blockedUid} = { blockedUid, createdAt }. 본인만 읽고, 쓰기는 이 서버만 한다.
 *   매칭 검색(searchMatches)·요청 생성·수락은 이미 양방향으로 이 문서를 확인한다.
 * - 대기 요청: 두 사람 사이 PENDING 을 같은 트랜잭션에서 끝낸다. 내가 보낸 것은 CANCELED, 받은 것은 REJECTED.
 *   공유 요청 문서에는 상태만 남기고 terminalReason 은 쓰지 않는다(차단 사실 비공개).
 *   직접 취소가 아니므로 60초 재신청 제한(cooldownUntil)을 만들지 않는다.
 * - 동시성: 요청 생성(후보 재검증)·수락은 같은 트랜잭션에서 blockedUsers 문서를 읽으므로, 차단이 먼저 커밋되면
 *   뒤따르는 생성·수락은 다시 실행되어 거부된다. 수락이 먼저 커밋된 확정 매칭은 그대로 둔다(BT-046 결정).
 * - 채팅: 기록은 그대로 두고 새 메시지만 막는다. 두 사람이 함께 쓰는 모든 방에
 *   chatRooms/{roomId}/blocks/{blockerUid} = true 를 쓰면 database.rules.json 이 전송을 거부한다.
 *   control(BT-034/038/043 이 관리)과 다른 노드라 방 상태 작업과 겹치지 않는다.
 *   화면 표시는 userChatRooms 의 blockedByMe(차단한 사람)·blockedByPartner(차단당한 사람)로 한다.
 * - Firestore 와 RTDB 는 한 트랜잭션이 아니다. RTDB 반영이 실패하면 오류를 돌려주고, 같은 요청을 다시 보내면
 *   차단 문서가 이미 있어도 RTDB 반영을 다시 한다(멱등).
 */

export const MAX_BLOCKED_USERS = 200;
export const blockInput = z.object({ targetUid: z.string().min(1).max(128).regex(/^[A-Za-z0-9_-]+$/) }).strict();
export type BlockInput = z.infer<typeof blockInput>;

export interface BlockResult { targetUid: string; blocked: boolean; closedRequests: number; rooms: number }

const fail = (code: ConstructorParameters<typeof HttpsError>[0], reason: string, message: string) =>
  new HttpsError(code, message, { reason, retryable: false });

export class BlockService {
  constructor(private readonly db: Firestore, private readonly rtdb: Database,
    private readonly now: () => Date = () => new Date()) {}

  async block(uid: string, targetUid: string): Promise<BlockResult> {
    if (uid === targetUid) throw fail("invalid-argument", "SELF_BLOCK", "자기 자신은 차단할 수 없어요.");
    const ref = this.db.doc(`users/${uid}/blockedUsers/${targetUid}`);
    const closedRequests = await this.db.runTransaction(async tx => {
      const [existing, target] = await Promise.all([tx.get(ref), tx.get(this.db.doc(`users/${targetUid}`))]);
      if (!existing.exists) {
        if (!target.exists) throw fail("not-found", "USER_NOT_FOUND", "사용자를 찾을 수 없어요.");
        const count = await tx.get(this.db.collection(`users/${uid}/blockedUsers`).limit(MAX_BLOCKED_USERS));
        if (count.size >= MAX_BLOCKED_USERS) {
          throw fail("resource-exhausted", "BLOCK_LIMIT", `차단은 최대 ${MAX_BLOCKED_USERS}명까지 할 수 있어요.`);
        }
      }
      const requests = this.db.collection("matchRequests");
      const [sent, received] = await Promise.all([
        tx.get(requests.where("fromUid", "==", uid).where("toUid", "==", targetUid).where("status", "==", "PENDING")),
        tx.get(requests.where("fromUid", "==", targetUid).where("toUid", "==", uid).where("status", "==", "PENDING")),
      ]);
      const touched = sent.size + received.size;
      const guards = touched > 0
        ? await Promise.all([uid, targetUid].map(id => tx.get(this.db.doc(`requestUserGuards/${id}`))))
        : [];
      const at = Timestamp.fromDate(this.now());
      if (!existing.exists) tx.create(ref, { blockedUid: targetUid, createdAt: at });
      // 요청 문서는 두 사람이 모두 읽으므로 상태만 바꾼다. 차단 사유(terminalReason)를 남기면 상대가 차단 사실을 알게 된다(#129 리뷰 D).
      // 상대에게는 일반 거절·취소와 같게 보인다. 차단 기록은 본인만 읽는 blockedUsers 에만 있다.
      const close = (doc: QueryDocumentSnapshot, status: "CANCELED" | "REJECTED") =>
        tx.update(doc.ref, { status, updatedAt: at });
      sent.docs.forEach(doc => close(doc, "CANCELED"));
      received.docs.forEach(doc => close(doc, "REJECTED"));
      // 요청 생성·수락과 같은 사용자 잠금 문서에서 경합하게 한다(BT-031). cooldownUntil 은 건드리지 않는다.
      guards.forEach((guard, i) => tx.set(this.db.doc(`requestUserGuards/${[uid, targetUid][i]}`),
        { revision: FieldValue.increment(1) }, { merge: true }));
      return touched;
    });
    const rooms = await this.syncRooms(uid, targetUid, true);
    return { targetUid, blocked: true, closedRequests, rooms };
  }

  async unblock(uid: string, targetUid: string): Promise<BlockResult> {
    if (uid === targetUid) throw fail("invalid-argument", "SELF_BLOCK", "자기 자신은 차단할 수 없어요.");
    await this.db.doc(`users/${uid}/blockedUsers/${targetUid}`).delete();
    // 상대가 탈퇴했어도 남은 방의 내 차단 표시는 지운다. 상대가 나를 차단한 표시는 그대로 둔다.
    const rooms = await this.syncRooms(uid, targetUid, false);
    return { targetUid, blocked: false, closedRequests: 0, rooms };
  }

  /** 두 사람이 함께 쓰는(썼던) 모든 방에 차단 표시를 쓰거나 지운다. 없는 목록 항목은 새로 만들지 않는다. */
  private async syncRooms(uid: string, targetUid: string, blocked: boolean): Promise<number> {
    const pairId = chatPairKey([uid, targetUid]);
    const [links, pair] = await Promise.all([
      this.db.collection("chatRoomLinks").where("pairId", "==", pairId).get(),
      this.db.doc(`chatPairs/${pairId}`).get(),
    ]);
    const roomIds = new Set(links.docs.map(doc => doc.id));
    const current = pair.get("roomId");
    if (typeof current === "string" && current) roomIds.add(current);
    const value = blocked ? true : null;
    const patch: Record<string, unknown> = {};
    for (const roomId of roomIds) {
      patch[`chatRooms/${roomId}/blocks/${uid}`] = value;
      const [mine, theirs] = await Promise.all([
        this.rtdb.ref(`userChatRooms/${uid}/${roomId}/otherUid`).get(),
        this.rtdb.ref(`userChatRooms/${targetUid}/${roomId}/otherUid`).get(),
      ]);
      if (mine.val() === targetUid) patch[`userChatRooms/${uid}/${roomId}/blockedByMe`] = value;
      if (theirs.val() === uid) patch[`userChatRooms/${targetUid}/${roomId}/blockedByPartner`] = value;
    }
    if (Object.keys(patch).length > 0) await this.rtdb.ref().update(patch);
    return roomIds.size;
  }
}
