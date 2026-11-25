import type { Database } from "firebase-admin/database";
import { FieldValue, Timestamp, type DocumentReference, type Firestore } from "firebase-admin/firestore";

/**
 * B · BT-045 회원 탈퇴 (docs/BT-045.md). 팀 결정: 내 개인 데이터는 삭제하고, 상대에게 남는 기록은 UID 만 남긴다(비식별).
 *
 * 순서(각 단계는 멱등이라 중간에 실패해도 같은 사용자가 다시 호출하면 이어서 끝낸다):
 *  1. 검색·신규 요청에서 즉시 빠지도록 matchSettings·publicProfiles 를 먼저 지운다.
 *  2. 대기 요청: 보낸 것 CANCELED, 받은 것 REJECTED, terminalReason = ACCOUNT_DELETED (BT-046 차단 정리와 같은 방식).
 *  3. ACTIVE 확정 매칭: ENDED, endReason = ACCOUNT_DELETED, reviewEligible = false (BT-038 종료 스키마).
 *  4. 채팅방: 나가기와 같게 처리. 내 멤버 권한·목록 항목을 지우고 방은 보관(읽기 전용)한다.
 *     남은 상대는 이전 대화를 읽을 수 있다(BT-034 한 명 이탈). 메시지는 지우지 않는다(신고 검토용, 보관 기간은 후속 합의).
 *  5. 개인 데이터 삭제: users(+blockedUsers)·timetables(+courses)·availability·notifications·requestUserGuards·
 *     aiUsage·matchScores(양방향)·Storage(profileImages·timetableImages)·RTDB userChatRooms/{uid}.
 *  6. 마지막에 Auth 계정을 지운다. 같은 학교 이메일로 다시 가입하면 새 UID 의 새 계정이다.
 *
 * 남기는 것: matchRequests·matches 의 시간 스냅샷(상대 기록 보존, BT-047)과 채팅 메시지(senderId 는 UID 뿐).
 * 상대 화면은 공개 프로필이 없어 "알 수 없는 사용자"로 보인다. 다른 사람의 차단 목록에 남은 내 UID 도 그대로 둔다.
 * 진행 상황은 서버 전용 accountDeletions/{uid} 에 남긴다(Rules 기본 거부).
 */

export interface AccountFiles {
  /** profileImages/{uid}/, timetableImages/{uid}/ 아래 파일을 모두 지운다. 없으면 성공. */
  deleteUserFiles(uid: string): Promise<void>;
}

export interface AccountAuth {
  /** Auth 사용자 삭제. 이미 없으면 성공으로 본다. */
  deleteUser(uid: string): Promise<void>;
}

export interface DeletionResult {
  deleted: true;
  closedRequests: number;
  endedMatches: number;
  leftRooms: number;
}

const PAGE = 100;

/** 진행 중인 채팅 연결이 끝나야 탈퇴를 마칠 수 있다. 잠시 뒤 다시 호출하면 이어서 진행한다. */
export class AccountDeletionBusy extends Error {
  constructor(readonly retryAfterMillis: number) {
    super("chat link in progress");
  }
}

export class AccountDeletionService {
  constructor(private readonly db: Firestore, private readonly rtdb: Database,
    private readonly files: AccountFiles, private readonly auth: AccountAuth,
    private readonly now: () => Date = () => new Date()) {}

  async delete(uid: string): Promise<DeletionResult> {
    const jobRef = this.db.doc(`accountDeletions/${uid}`);
    await this.db.runTransaction(async tx => {
      const job = await tx.get(jobRef);
      tx.set(jobRef, { uid, status: "RUNNING", attempts: FieldValue.increment(1),
        ...(job.exists ? {} : { startedAt: this.timestamp() }), updatedAt: this.timestamp() }, { merge: true });
    });
    try {
      await this.hideFromMatching(uid);
      const closedRequests = await this.closeRequests(uid);
      const endedMatches = await this.endMatches(uid);
      await this.waitForChatLinks(uid);
      const leftRooms = await this.leaveRooms(uid);
      await this.deletePersonalData(uid);
      await this.files.deleteUserFiles(uid);
      await this.auth.deleteUser(uid);
      await jobRef.set({ status: "DONE", finishedAt: this.timestamp(), updatedAt: this.timestamp() }, { merge: true });
      return { deleted: true, closedRequests, endedMatches, leftRooms };
    } catch (error) {
      await jobRef.set({ status: "FAILED", updatedAt: this.timestamp() }, { merge: true }).catch(() => undefined);
      throw error;
    }
  }

  private timestamp() {
    return Timestamp.fromDate(this.now());
  }

  /** 검색 후보(matchSettings)와 요청 생성·수락의 공개 프로필 검사에서 바로 빠진다. */
  private async hideFromMatching(uid: string) {
    const batch = this.db.batch();
    batch.delete(this.db.doc(`matchSettings/${uid}`));
    batch.delete(this.db.doc(`publicProfiles/${uid}`));
    await batch.commit();
  }

  private async closeRequests(uid: string): Promise<number> {
    let closed = 0;
    for (const [field, status] of [["fromUid", "CANCELED"], ["toUid", "REJECTED"]] as const) {
      for (;;) {
        const page = await this.db.collection("matchRequests").where(field, "==", uid)
          .where("status", "==", "PENDING").limit(PAGE).get();
        if (page.empty) break;
        for (const doc of page.docs) if (await this.closeRequest(doc.ref, status)) closed++;
        if (page.size < PAGE) break;
      }
    }
    return closed;
  }

  /** 요청 하나를 트랜잭션으로 끝낸다. 그 사이 상대가 수락·취소했으면 건드리지 않는다. */
  private async closeRequest(ref: DocumentReference, status: "CANCELED" | "REJECTED"): Promise<boolean> {
    return this.db.runTransaction(async tx => {
      const doc = await tx.get(ref);
      if (doc.get("status") !== "PENDING") return false;
      const parties = [doc.get("fromUid"), doc.get("toUid")].filter((u): u is string => typeof u === "string" && !!u);
      // 요청 생성·수락(BT-031)과 같은 사용자 잠금 문서에서 경합하게 한다. 재신청 제한(cooldownUntil)은 만들지 않는다.
      await Promise.all(parties.map(u => tx.get(this.db.doc(`requestUserGuards/${u}`))));
      tx.update(ref, { status, terminalReason: "ACCOUNT_DELETED", updatedAt: this.timestamp() });
      for (const u of parties) tx.set(this.db.doc(`requestUserGuards/${u}`), { revision: FieldValue.increment(1) }, { merge: true });
      return true;
    });
  }

  private async endMatches(uid: string): Promise<number> {
    const matches = await this.db.collection("matches").where("participantUids", "array-contains", uid).get();
    let ended = 0;
    for (const match of matches.docs) {
      if (match.get("status") !== "ACTIVE") continue;
      const changed = await this.db.runTransaction(async tx => {
        const doc = await tx.get(match.ref);
        if (doc.get("status") !== "ACTIVE") return false;
        const at = this.timestamp();
        // BT-038 종료 스키마. 상대가 남긴 매칭은 후기 대상이 아니다.
        tx.update(match.ref, { status: "ENDED", endedAt: at, endReason: "ACCOUNT_DELETED", endedBy: null,
          endProcessedAt: at, reviewEligible: false, updatedAt: at });
        return true;
      });
      if (changed) ended++;
    }
    return ended;
  }

  /**
   * #130 리뷰(D): 탈퇴 시작(accountDeletions) 뒤에는 ChatLinkService 가 새 연결을 거부한다. 그 전에 시작해 아직 lease 가
   * 남은 연결은 RTDB 에 멤버·목록을 쓸 수 있으므로, 끝날 때까지 탈퇴를 미룬다(재시도 가능한 오류). 연결이 끝나거나
   * lease 가 지나면 다시 호출했을 때 그 방까지 아래 leaveRooms 가 정리한다.
   */
  private async waitForChatLinks(uid: string) {
    const matches = await this.db.collection("matches").where("participantUids", "array-contains", uid).get();
    const now = this.now().getTime();
    for (const match of matches.docs) {
      const job = await this.db.doc(`chatLinkJobs/${match.id}`).get();
      const lease = job.get("leaseUntil");
      if (job.get("status") === "PREPARING" && lease instanceof Timestamp && lease.toMillis() > now) {
        throw new AccountDeletionBusy(lease.toMillis() - now);
      }
    }
  }

  /** 내 목록과 참여 기록에 있는 모든 방에서 나간다. 남은 사람은 보관된 방을 읽기만 한다. */
  private async leaveRooms(uid: string): Promise<number> {
    const [entries, links] = await Promise.all([
      this.rtdb.ref(`userChatRooms/${uid}`).get(),
      this.db.collection("chatRoomLinks").where("participantUids", "array-contains", uid).get(),
    ]);
    const roomIds = new Set<string>(links.docs.map(doc => doc.id));
    entries.forEach(child => { if (child.key) roomIds.add(child.key); });
    for (const roomId of roomIds) {
      const members = Object.keys((await this.rtdb.ref(`chatRooms/${roomId}/members`).get()).val() ?? {});
      const remaining = members.filter(member => member !== uid);
      const patch: Record<string, unknown> = {
        [`chatRooms/${roomId}/members/${uid}`]: null,
        // 남은 사람은 과거 기록만 읽는다(BT-034 보관 방). 보관 방은 새 매칭에 다시 쓰이지 않는다.
        [`chatRooms/${roomId}/control`]: { archived: true, writable: false, chatReady: false },
      };
      for (const member of remaining) {
        const entry = await this.rtdb.ref(`userChatRooms/${member}/${roomId}/otherUid`).get();
        if (!entry.exists()) continue; // 지워진 목록 항목을 부분 필드로 다시 만들지 않는다
        patch[`userChatRooms/${member}/${roomId}/archived`] = true;
        patch[`userChatRooms/${member}/${roomId}/chatReady`] = false;
        patch[`userChatRooms/${member}/${roomId}/status`] = "ENDED";
      }
      // 한 번의 multi-path update 로 멤버 제거·보관을 함께 적용한다(BT-034 §4). 내 목록은 5단계에서 통째로 지운다.
      await this.rtdb.ref().update(patch);
      await this.db.doc(`chatRoomLinks/${roomId}`).set({ participantUids: remaining, archived: true, chatReady: false,
        updatedAt: this.timestamp() }, { merge: true });
    }
    return roomIds.size;
  }

  private async deletePersonalData(uid: string) {
    for (const path of [`users/${uid}`, `timetables/${uid}`, `notifications/${uid}`]) {
      await this.db.recursiveDelete(this.db.doc(path)); // 하위 컬렉션(blockedUsers·courses·items)까지
    }
    const batch = this.db.batch();
    for (const path of [`availability/${uid}`, `requestUserGuards/${uid}`, `matchSettings/${uid}`, `publicProfiles/${uid}`]) {
      batch.delete(this.db.doc(path));
    }
    await batch.commit();
    await this.deleteWhere("aiUsage", "uid", uid);
    await this.deleteWhere("matchScores", "searcherUid", uid);
    await this.deleteWhere("matchScores", "candidateUid", uid);
    await this.rtdb.ref(`userChatRooms/${uid}`).remove();
  }

  private async deleteWhere(collection: string, field: string, uid: string) {
    for (;;) {
      const page = await this.db.collection(collection).where(field, "==", uid).limit(PAGE).get();
      if (page.empty) return;
      const batch = this.db.batch();
      page.docs.forEach(doc => batch.delete(doc.ref));
      await batch.commit();
      if (page.size < PAGE) return;
    }
  }
}
