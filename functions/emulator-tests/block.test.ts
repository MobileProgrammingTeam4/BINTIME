import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getDatabase } from "firebase-admin/database";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, setDoc, deleteDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { ChatLinkService } from "../src/chat/link";
import { FirestoreMatchStore } from "../src/matching/search";
import { BlockService, MAX_BLOCKED_USERS } from "../src/safety/block";

/**
 * BT-046 차단·해제. 로컬 Firestore·RTDB 에뮬레이터에서만 실행한다.
 *   firebase emulators:exec --project demo-bintime --only firestore,database "npm --prefix functions run test:emulator"
 */
const projectId = "demo-bintime-block";
const app = initializeApp({ projectId, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? "127.0.0.1:9000"}?ns=${projectId}` }, `block-${randomUUID()}`);
const db = getFirestore(app), rtdb = getDatabase(app);
const NOW = Date.parse("2026-11-25T01:00:00Z");
const ME = "u0", OTHER = "u1", THIRD = "u2";
const MATCH = "a".repeat(64);
let env: RulesTestEnvironment;
const service = () => new BlockService(db, rtdb, () => new Date(NOW));
const message = (uid: string) => ({ senderId: uid, content: "안녕하세요", createdAt: { ".sv": "timestamp" } });
const send = (uid: string, id: string) =>
  env.authenticatedContext(uid).database().ref(`chatRooms/${MATCH}/messages/${id}`).set(message(uid));

async function seedRequest(id: string, fromUid: string, toUid: string, status = "PENDING") {
  await db.doc(`matchRequests/${id}`).set({ fromUid, toUid, status, purpose: "스터디", meetingDate: "2026-11-25",
    dayOfWeek: 3, startMinute: 780, endMinute: 900, overlapMinutes: 120,
    createdAt: Timestamp.fromMillis(NOW - 60_000), updatedAt: Timestamp.fromMillis(NOW - 60_000) });
}

async function linkRoom() {
  await db.doc(`matches/${MATCH}`).set({ requestId: MATCH, participantUids: [ME, OTHER], purpose: "스터디",
    meetingDate: "2026-11-25", startMinute: 780, endMinute: 900, status: "ACTIVE", chatReady: false,
    createdAt: Timestamp.fromMillis(NOW) });
  await new ChatLinkService(db, rtdb, () => new Date(NOW)).link(MATCH);
}

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST)("BT-046 차단·해제", () => {
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
    await env.clearFirestore(); await env.clearDatabase();
    for (const uid of [ME, OTHER, THIRD]) await db.doc(`users/${uid}`).set({ uid, nickname: uid, department: "컴퓨터공학과" });
  });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("차단 기록을 만들고 두 사람 사이 대기 요청만 끝낸다(재신청 제한 없음)", async () => {
    await seedRequest("sent", ME, OTHER);
    await seedRequest("received", OTHER, ME);
    await seedRequest("other", ME, THIRD);
    await seedRequest("done", ME, OTHER, "ACCEPTED");

    const result = await service().block(ME, OTHER);
    expect(result).toMatchObject({ targetUid: OTHER, blocked: true, closedRequests: 2 });

    expect((await db.doc(`users/${ME}/blockedUsers/${OTHER}`).get()).data())
      .toEqual({ blockedUid: OTHER, createdAt: Timestamp.fromMillis(NOW) });
    expect((await db.doc("matchRequests/sent").get()).data()).toMatchObject({ status: "CANCELED", terminalReason: "BLOCKED" });
    expect((await db.doc("matchRequests/received").get()).data()).toMatchObject({ status: "REJECTED", terminalReason: "BLOCKED" });
    expect((await db.doc("matchRequests/other").get()).get("status")).toBe("PENDING");
    expect((await db.doc("matchRequests/done").get()).get("status")).toBe("ACCEPTED");
    // 직접 취소가 아니므로 60초 재신청 제한을 만들지 않는다.
    expect((await db.doc(`requestUserGuards/${ME}`).get()).get("cooldownUntil")).toBeUndefined();
    expect((await db.doc(`requestUserGuards/${ME}`).get()).get("revision")).toBe(1);

    // 다시 보내도 같은 결과(멱등). 이미 끝난 요청은 다시 바꾸지 않는다.
    expect(await service().block(ME, OTHER)).toMatchObject({ blocked: true, closedRequests: 0 });
    expect((await db.collection(`users/${ME}/blockedUsers`).get()).size).toBe(1);
  });

  it("자기 자신·없는 사용자는 차단하지 않는다", async () => {
    await expect(service().block(ME, ME)).rejects.toMatchObject({ code: "invalid-argument" });
    await expect(service().block(ME, "ghost")).rejects.toMatchObject({ code: "not-found" });
    expect((await db.collection(`users/${ME}/blockedUsers`).get()).empty).toBe(true);
  });

  it(`차단은 ${MAX_BLOCKED_USERS}명까지다`, async () => {
    const batch = db.batch();
    for (let i = 0; i < MAX_BLOCKED_USERS; i++) batch.set(db.doc(`users/${ME}/blockedUsers/x${i}`), { blockedUid: `x${i}` });
    await batch.commit();
    await expect(service().block(ME, OTHER)).rejects.toMatchObject({ code: "resource-exhausted" });
  });

  it("차단 목록은 본인만 읽고, 앱은 직접 쓰지 못한다", async () => {
    await service().block(ME, OTHER);
    const mine = env.authenticatedContext(ME).firestore();
    await assertSucceeds(getDocs(collection(mine, `users/${ME}/blockedUsers`)));
    await assertFails(setDoc(doc(mine, `users/${ME}/blockedUsers/${THIRD}`), { blockedUid: THIRD }));
    await assertFails(deleteDoc(doc(mine, `users/${ME}/blockedUsers/${OTHER}`)));
    // 차단당한 사람도, 제3자도 남의 차단 목록을 읽지 못한다.
    for (const uid of [OTHER, THIRD]) {
      const client = env.authenticatedContext(uid).firestore();
      await assertFails(getDoc(doc(client, `users/${ME}/blockedUsers/${OTHER}`)));
      await assertFails(getDocs(collection(client, `users/${ME}/blockedUsers`)));
    }
    await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(), `users/${ME}/blockedUsers`)));
  });

  it("기존 채팅은 기록을 남기고 양쪽 새 메시지만 막으며, 해제하면 다시 보낼 수 있다", async () => {
    await linkRoom();
    await assertSucceeds(send(ME, "before"));

    await service().block(ME, OTHER);
    await assertFails(send(ME, "m1"));
    await assertFails(send(OTHER, "m2"));
    // 기록은 그대로 읽힌다.
    await assertSucceeds(env.authenticatedContext(OTHER).database().ref(`chatRooms/${MATCH}/messages`).get());
    expect((await rtdb.ref(`chatRooms/${MATCH}/messages/before/content`).get()).val()).toBe("안녕하세요");
    expect((await rtdb.ref(`userChatRooms/${ME}/${MATCH}`).get()).val()).toMatchObject({ blockedByMe: true, otherUid: OTHER });
    expect((await rtdb.ref(`userChatRooms/${OTHER}/${MATCH}`).get()).val()).toMatchObject({ blockedByPartner: true, otherUid: ME });
    // 차단 표시는 서버 전용: 앱이 지우거나 쓸 수 없다.
    await assertFails(env.authenticatedContext(OTHER).database().ref(`chatRooms/${MATCH}/blocks/${ME}`).remove());
    await assertFails(env.authenticatedContext(ME).database().ref(`userChatRooms/${ME}/${MATCH}/blockedByMe`).remove());

    await service().unblock(ME, OTHER);
    expect((await db.doc(`users/${ME}/blockedUsers/${OTHER}`).get()).exists).toBe(false);
    expect((await rtdb.ref(`userChatRooms/${ME}/${MATCH}/blockedByMe`).get()).exists()).toBe(false);
    expect((await rtdb.ref(`userChatRooms/${OTHER}/${MATCH}/blockedByPartner`).get()).exists()).toBe(false);
    await assertSucceeds(send(ME, "m3"));
    await assertSucceeds(send(OTHER, "m4"));
  });

  it("서로 차단했으면 한 사람이 해제해도 전송은 계속 막힌다", async () => {
    await linkRoom();
    await service().block(ME, OTHER);
    await service().block(OTHER, ME);
    await service().unblock(ME, OTHER);
    await assertFails(send(ME, "m1"));
    expect((await rtdb.ref(`userChatRooms/${ME}/${MATCH}`).get()).val()).toMatchObject({ blockedByPartner: true });
    expect((await rtdb.ref(`userChatRooms/${ME}/${MATCH}/blockedByMe`).get()).exists()).toBe(false);
    await service().unblock(OTHER, ME);
    await assertSucceeds(send(ME, "m2"));
  });

  it("매칭 검색 저장소가 차단·해제를 바로 읽는다(검색·요청 생성·수락이 같은 경로를 양방향 확인)", async () => {
    const store = new FirestoreMatchStore(db);
    await service().block(ME, OTHER);
    expect(await store.blocked(ME, OTHER)).toBe(true);
    expect(await store.blocked(OTHER, ME)).toBe(false); // 검색은 두 방향을 모두 본다(search.ts eitherBlocked)
    await service().unblock(ME, OTHER);
    expect(await store.blocked(ME, OTHER)).toBe(false); // 해제 후 다음 검색부터 다시 후보가 된다
  });

  it("차단 해제는 차단한 적 없어도 실패하지 않는다", async () => {
    await expect(service().unblock(ME, OTHER)).resolves.toMatchObject({ blocked: false });
  });
});
