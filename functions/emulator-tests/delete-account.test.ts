import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getDatabase } from "firebase-admin/database";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { ChatLinkService } from "../src/chat/link";
import { AccountDeletionService } from "../src/account/deleteAccount";

/**
 * BT-045 회원 탈퇴. 로컬 Firestore·RTDB 에뮬레이터에서만 실행한다. Storage·Auth 는 가짜 구현으로 호출만 확인한다.
 *   firebase emulators:exec --project demo-bintime --only firestore,database "npm --prefix functions run test:emulator"
 */
const projectId = "demo-bintime-delete";
const app = initializeApp({ projectId, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? "127.0.0.1:9000"}?ns=${projectId}` }, `delete-${randomUUID()}`);
const db = getFirestore(app), rtdb = getDatabase(app);
const NOW = Date.parse("2026-11-25T03:00:00Z"); // 12:00 KST
const ME = "u0", OTHER = "u1", THIRD = "u2";
const ROOM = "a".repeat(64), PAST = "b".repeat(64);
let env: RulesTestEnvironment;
let deletedFiles: string[], deletedUsers: string[], failAuth: boolean;

const service = () => new AccountDeletionService(db, rtdb,
  { async deleteUserFiles(uid) { deletedFiles.push(uid); } },
  { async deleteUser(uid) { if (failAuth) throw new Error("auth down"); deletedUsers.push(uid); } },
  () => new Date(NOW));
const message = (uid: string) => ({ senderId: uid, content: "안녕하세요", createdAt: { ".sv": "timestamp" } });
const exists = async (path: string) => (await db.doc(path).get()).exists;

async function seedUser(uid: string) {
  await db.doc(`users/${uid}`).set({ uid, nickname: uid, department: "컴퓨터공학과", email: `${uid}@kookmin.ac.kr`, profileCompleted: true });
  await db.doc(`publicProfiles/${uid}`).set({ uid, nickname: uid, department: "컴퓨터공학과" });
  await db.doc(`timetables/${uid}`).set({ uid, revision: 1 });
  await db.doc(`timetables/${uid}/courses/c1`).set({ courseId: "c1", name: "자료구조" });
  await db.doc(`availability/${uid}`).set({ slots: [], sourceRevision: 1 });
  await db.doc(`matchSettings/${uid}`).set({ isMatching: true, minOverlapMinutes: 60, purposes: [], preferredTags: [] });
  await db.doc(`aiUsage/${uid}_2026-11-25`).set({ uid, date: "2026-11-25", total: 1 });
  await db.doc(`requestUserGuards/${uid}`).set({ revision: 3 });
}

async function seedRequest(id: string, fromUid: string, toUid: string, status = "PENDING") {
  await db.doc(`matchRequests/${id}`).set({ fromUid, toUid, status, purpose: "스터디", meetingDate: "2026-11-25",
    startMinute: 780, endMinute: 900, overlapMinutes: 120, createdAt: Timestamp.fromMillis(NOW - 60_000) });
}

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST)("BT-045 회원 탈퇴", () => {
  beforeAll(async () => {
    for (const host of [process.env.FIRESTORE_EMULATOR_HOST, process.env.FIREBASE_DATABASE_EMULATOR_HOST]) {
      if (!/^(127\.0\.0\.1|localhost):/.test(host ?? "")) throw new Error("local emulators required");
    }
    const [host, port] = process.env.FIREBASE_DATABASE_EMULATOR_HOST!.split(":");
    env = await initializeTestEnvironment({ projectId,
      firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
      database: { host, port: Number(port), rules: readFileSync(new URL("../../database.rules.json", import.meta.url), "utf8") } });
  });
  beforeEach(async () => {
    deletedFiles = []; deletedUsers = []; failAuth = false;
    await env.clearFirestore(); await env.clearDatabase();
    for (const uid of [ME, OTHER, THIRD]) await seedUser(uid);
    await db.doc(`users/${ME}/blockedUsers/${THIRD}`).set({ blockedUid: THIRD });
    await db.doc(`users/${OTHER}/blockedUsers/${ME}`).set({ blockedUid: ME });
    await db.doc(`matchScores/${ME}_${OTHER}`).set({ searcherUid: ME, candidateUid: OTHER, score: 80 });
    await db.doc(`matchScores/${OTHER}_${ME}`).set({ searcherUid: OTHER, candidateUid: ME, score: 70 });
    await db.doc(`matchScores/${OTHER}_${THIRD}`).set({ searcherUid: OTHER, candidateUid: THIRD, score: 60 });
    await seedRequest("sent", ME, OTHER);
    await seedRequest("received", THIRD, ME);
    await seedRequest("accepted", ME, OTHER, "ACCEPTED");
    await seedRequest("unrelated", OTHER, THIRD);
    // ACTIVE 매칭 + 채팅방 연결, 이전에 끝난 매칭 하나
    await db.doc(`matches/${ROOM}`).set({ requestId: ROOM, participantUids: [ME, OTHER], purpose: "스터디",
      meetingDate: "2026-11-25", startMinute: 780, endMinute: 900, status: "ACTIVE", chatReady: false,
      createdAt: Timestamp.fromMillis(NOW) });
    await new ChatLinkService(db, rtdb, () => new Date(NOW)).link(ROOM);
    await db.doc(`matches/${PAST}`).set({ requestId: PAST, participantUids: [ME, THIRD], meetingDate: "2026-11-24",
      startMinute: 600, endMinute: 700, status: "ENDED", endReason: "AUTO", reviewEligible: true });
    await env.authenticatedContext(ME).database().ref(`chatRooms/${ROOM}/messages/m1`).set(message(ME));
  });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("개인 데이터를 지우고 상대 기록은 UID 만 남긴 채 보존한다", async () => {
    const result = await service().delete(ME);
    expect(result).toEqual({ deleted: true, closedRequests: 2, endedMatches: 1, leftRooms: 1 });

    for (const path of [`users/${ME}`, `users/${ME}/blockedUsers/${THIRD}`, `publicProfiles/${ME}`, `timetables/${ME}`,
      `timetables/${ME}/courses/c1`, `availability/${ME}`, `matchSettings/${ME}`, `aiUsage/${ME}_2026-11-25`,
      `requestUserGuards/${ME}`, `matchScores/${ME}_${OTHER}`, `matchScores/${OTHER}_${ME}`]) {
      expect(await exists(path), path).toBe(false);
    }
    // 다른 사용자의 데이터는 그대로
    for (const path of [`users/${OTHER}`, `publicProfiles/${OTHER}`, `timetables/${OTHER}/courses/c1`,
      `matchScores/${OTHER}_${THIRD}`, `users/${OTHER}/blockedUsers/${ME}`]) {
      expect(await exists(path), path).toBe(true);
    }
    expect(deletedFiles).toEqual([ME]);
    expect(deletedUsers).toEqual([ME]);
    expect((await db.doc(`accountDeletions/${ME}`).get()).data()).toMatchObject({ uid: ME, status: "DONE", attempts: 1 });
  });

  it("대기 요청은 끝내고, 진행 매칭은 후기 없이 종료하며, 확정·지난 기록과 시간 스냅샷은 보존한다", async () => {
    await service().delete(ME);
    expect((await db.doc("matchRequests/sent").get()).data()).toMatchObject({ status: "CANCELED", terminalReason: "ACCOUNT_DELETED", startMinute: 780 });
    expect((await db.doc("matchRequests/received").get()).data()).toMatchObject({ status: "REJECTED", terminalReason: "ACCOUNT_DELETED" });
    expect((await db.doc("matchRequests/accepted").get()).get("status")).toBe("ACCEPTED");
    expect((await db.doc("matchRequests/unrelated").get()).get("status")).toBe("PENDING");
    // 재신청 제한은 만들지 않는다(상대의 잠금 문서는 revision 만 오른다)
    expect((await db.doc(`requestUserGuards/${THIRD}`).get()).data()).toEqual({ revision: 4 });

    expect((await db.doc(`matches/${ROOM}`).get()).data()).toMatchObject({ status: "ENDED", endReason: "ACCOUNT_DELETED",
      reviewEligible: false, endedBy: null, startMinute: 780, endMinute: 900, participantUids: [ME, OTHER] });
    expect((await db.doc(`matches/${PAST}`).get()).data()).toMatchObject({ status: "ENDED", endReason: "AUTO", reviewEligible: true });
  });

  it("채팅방은 나가기처럼 처리한다: 상대는 이전 대화만 읽고, 탈퇴한 계정은 접근할 수 없다", async () => {
    await service().delete(ME);
    expect((await rtdb.ref(`chatRooms/${ROOM}/members`).get()).val()).toEqual({ [OTHER]: expect.anything() });
    expect((await rtdb.ref(`chatRooms/${ROOM}/messages/m1/content`).get()).val()).toBe("안녕하세요"); // 메시지는 보관
    expect((await rtdb.ref(`userChatRooms/${ME}`).get()).exists()).toBe(false);
    expect((await rtdb.ref(`userChatRooms/${OTHER}/${ROOM}`).get()).val()).toMatchObject({ archived: true, chatReady: false, status: "ENDED", otherUid: ME });
    expect((await db.doc(`chatRoomLinks/${ROOM}`).get()).data()).toMatchObject({ participantUids: [OTHER], archived: true });

    const other = env.authenticatedContext(OTHER).database();
    await assertSucceeds(other.ref(`chatRooms/${ROOM}/messages`).get());
    await assertFails(other.ref(`chatRooms/${ROOM}/messages/m2`).set(message(OTHER)));
    const me = env.authenticatedContext(ME).database(); // 토큰이 아직 남아 있어도
    await assertFails(me.ref(`chatRooms/${ROOM}/messages`).get());
    await assertFails(me.ref(`chatRooms/${ROOM}/messages/m3`).set(message(ME)));
  });

  it("중간에 실패해도 다시 호출하면 이어서 끝내고, 이미 끝난 탈퇴를 다시 불러도 안전하다", async () => {
    failAuth = true;
    await expect(service().delete(ME)).rejects.toThrow("auth down");
    expect((await db.doc(`accountDeletions/${ME}`).get()).get("status")).toBe("FAILED");
    expect(await exists(`users/${ME}`)).toBe(false); // 앞 단계는 이미 반영
    failAuth = false;
    expect(await service().delete(ME)).toEqual({ deleted: true, closedRequests: 0, endedMatches: 0, leftRooms: 0 });
    expect(deletedUsers).toEqual([ME]);
    expect((await db.doc(`accountDeletions/${ME}`).get()).data()).toMatchObject({ status: "DONE", attempts: 2 });
    await expect(service().delete(ME)).resolves.toMatchObject({ deleted: true });
  });

  it("탈퇴 진행 기록은 앱이 읽거나 쓸 수 없다", async () => {
    await service().delete(ME);
    await assertFails(getDoc(doc(env.authenticatedContext(ME).firestore(), `accountDeletions/${ME}`)));
    await assertFails(getDoc(doc(env.authenticatedContext(OTHER).firestore(), `accountDeletions/${ME}`)));
  });
});
