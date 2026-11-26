import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getDatabase, type Database } from "firebase-admin/database";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { ChatLeaveService } from "../src/chat/leave";
import { ChatLinkService, chatPairKey } from "../src/chat/link";

/** BT-043: 실제 연결 함수(BT-033)가 만든 방에서 나가기 정책·잠금·Rules 를 확인한다. */
const projectId = "demo-bintime-leave", first = "a".repeat(64), second = "b".repeat(64), third = "c".repeat(64);
const app = initializeApp({ projectId, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? "127.0.0.1:9000"}?ns=${projectId}` }, `leave-${randomUUID()}`);
const db = getFirestore(app), rtdb = getDatabase(app);
const clock = Date.parse("2026-11-24T00:00:00Z");
const pairId = chatPairKey(["u0", "u1"]);
let env: RulesTestEnvironment;
const leaver = (database: Database = rtdb) => new ChatLeaveService(db, database, () => new Date(clock));
const link = (id: string) => new ChatLinkService(db, rtdb, () => new Date(clock)).link(id);
const seed = (id: string, status = "ENDED") => db.doc(`matches/${id}`).set({ requestId: id, participantUids: ["u0", "u1"], purpose: "스터디",
  meetingDate: "2026-11-24", startMinute: 600, endMinute: 690, status: "ACTIVE", chatReady: false, createdAt: Timestamp.fromMillis(clock) })
  .then(() => link(id)).then(() => db.doc(`matches/${id}`).update({ status }));
const message = (uid: string) => ({ senderId: uid, content: "안녕하세요", createdAt: { ".sv": "timestamp" } });
const send = (uid: string, room = first) => env.authenticatedContext(uid).database().ref(`chatRooms/${room}/messages`).push(message(uid));
const read = (uid: string, room = first) => env.authenticatedContext(uid).database().ref(`chatRooms/${room}/messages`).get();
const val = async (path: string) => (await rtdb.ref(path).get()).val();
const reason = (r: string) => ({ code: "failed-precondition", details: expect.objectContaining({ reason: r }) });

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST)("BT-043 채팅방 나가기", () => {
  beforeAll(async () => {
    for (const host of [process.env.FIRESTORE_EMULATOR_HOST, process.env.FIREBASE_DATABASE_EMULATOR_HOST]) if (!/^(127\.0\.0\.1|localhost):/.test(host ?? "")) throw new Error("local emulators required");
    const [host, port] = process.env.FIREBASE_DATABASE_EMULATOR_HOST!.split(":");
    env = await initializeTestEnvironment({ projectId, firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
      database: { host, port: Number(port), rules: readFileSync(new URL("../../database.rules.json", import.meta.url), "utf8") } });
  });
  beforeEach(async () => { await env.clearFirestore(); await env.clearDatabase(); });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("ENDED 여도 둘 다 남은 방은 계속 송수신한다(종료는 나가기가 아님)", async () => {
    await seed(first);
    await assertSucceeds(send("u0"));
    await assertSucceeds(send("u1"));
    expect((await read("u1")).numChildren()).toBe(2);
  });

  it("연결된 약속 중 ACTIVE 가 하나라도 있으면 ACTIVE_MATCH 로 거절하고 아무것도 바꾸지 않는다", async () => {
    await seed(first, "ENDED");
    await seed(second, "ACTIVE"); // 같은 두 사람의 두 번째 약속은 같은 방을 재사용한다
    expect((await db.doc(`matches/${second}`).get()).get("chatRoomId")).toBe(first);
    await expect(leaver().leave(first, "u0")).rejects.toMatchObject(reason("ACTIVE_MATCH"));
    expect(Object.keys(await val(`chatRooms/${first}/members`)).sort()).toEqual(["u0", "u1"]);
    expect(await val(`userChatRooms/u0/${first}/hidden`)).toBe(false);
    const pair = (await db.doc(`chatPairs/${pairId}`).get()).data()!;
    expect(pair.roomStateOwner ?? null).toBeNull();
    expect(pair.stateRevision ?? 0).toBe(0);
  });

  it("첫 이탈: 멤버 제거·내 목록 숨김·방 보관·남은 사람은 이전 대화로 읽기만. 메시지·안읽음은 그대로", async () => {
    await seed(first);
    await assertSucceeds(send("u1"));
    await rtdb.ref(`userChatRooms/u0/${first}/unreadCount`).set(3);
    expect(await leaver().leave(first, "u0")).toEqual({ chatRoomId: first, left: true });

    expect(Object.keys(await val(`chatRooms/${first}/members`))).toEqual(["u1"]);
    expect(await val(`chatRooms/${first}/control`)).toEqual({ archived: true, writable: false, chatReady: false });
    expect(await val(`userChatRooms/u0/${first}`)).toMatchObject({ hidden: true, archived: true, chatReady: false, status: "ENDED", unreadCount: 3 });
    expect(await val(`userChatRooms/u1/${first}`)).toMatchObject({ hidden: false, archived: true, chatReady: false, status: "ENDED" });
    expect(Object.keys(await val(`chatRooms/${first}/messages`))).toHaveLength(1);
    expect((await db.doc(`chatRoomLinks/${first}`).get()).data()).toMatchObject({ participantUids: ["u1"], archived: true, chatReady: false, pairId });
    const pair = (await db.doc(`chatPairs/${pairId}`).get()).data()!;
    expect(pair).toMatchObject({ stateRevision: 1, roomStateOwner: null, roomStateLeaseUntil: null });

    // Rules: 나간 사람은 읽기·쓰기 모두 거부, 남은 사람은 읽기만.
    await assertFails(read("u0"));
    await assertFails(send("u0"));
    await assertSucceeds(read("u1"));
    await assertFails(send("u1"));
    await assertFails(getDoc(doc(env.authenticatedContext("u0").firestore(), "chatRoomLinks", first)));
    await assertSucceeds(getDoc(doc(env.authenticatedContext("u1").firestore(), "chatRoomLinks", first)));
  });

  it("같은 사람이 다시 부르면 바꾸지 않고 성공, 남은 사람이 나가면 둘 다 숨김·멤버 없음(서버 보관)", async () => {
    await seed(first);
    await assertSucceeds(send("u0"));
    await leaver().leave(first, "u0");
    expect(await leaver().leave(first, "u0")).toEqual({ chatRoomId: first, left: true });
    expect((await db.doc(`chatPairs/${pairId}`).get()).get("stateRevision")).toBe(1);

    await leaver().leave(first, "u1");
    expect(await val(`chatRooms/${first}/members`)).toBeNull();
    expect(await val(`userChatRooms/u0/${first}/hidden`)).toBe(true);
    expect(await val(`userChatRooms/u1/${first}/hidden`)).toBe(true);
    expect(Object.keys(await val(`chatRooms/${first}/messages`))).toHaveLength(1); // 메시지는 서버에 보관
    expect((await db.doc(`chatRoomLinks/${first}`).get()).get("participantUids")).toEqual([]);
    await assertFails(read("u1"));
  });

  it("비참여자·없는 방은 permission-denied", async () => {
    await seed(first);
    await expect(leaver().leave(first, "outsider")).rejects.toMatchObject({ code: "permission-denied" });
    await expect(leaver().leave(third, "u0")).rejects.toMatchObject({ code: "permission-denied" });
  });

  it("연결 중(pendingMatchId)이거나 유효한 상태 lease 가 있으면 RETRY_LATER, 만료된 lease 는 통과", async () => {
    await seed(first);
    const pairRef = db.doc(`chatPairs/${pairId}`);
    await pairRef.set({ pendingMatchId: second }, { merge: true });
    await expect(leaver().leave(first, "u0")).rejects.toMatchObject(reason("RETRY_LATER"));
    await pairRef.set({ pendingMatchId: null, roomStateOwner: "other-worker", roomStateLeaseUntil: Timestamp.fromMillis(clock + 30_000) }, { merge: true });
    const error = await leaver().leave(first, "u0").catch(e => e);
    expect(error).toMatchObject(reason("RETRY_LATER"));
    expect(error.details.retryAfterMillis).toBe(30_000);
    expect((await pairRef.get()).get("roomStateOwner")).toBe("other-worker"); // 남의 lease 를 건드리지 않는다
    await pairRef.set({ roomStateLeaseUntil: Timestamp.fromMillis(clock - 1) }, { merge: true });
    await expect(leaver().leave(first, "u0")).resolves.toMatchObject({ left: true });
  });

  it("두 사람이 동시에 나가도 하나씩 처리되고, 다시 시도하면 최종 상태가 같다", async () => {
    await seed(first);
    const results = await Promise.allSettled([leaver().leave(first, "u0"), leaver().leave(first, "u1")]);
    expect(results.some(r => r.status === "fulfilled")).toBe(true);
    for (const [i, r] of results.entries()) {
      if (r.status === "rejected") {
        expect(r.reason).toMatchObject(reason("RETRY_LATER"));
        await leaver().leave(first, i === 0 ? "u0" : "u1");
      }
    }
    expect(await val(`chatRooms/${first}/members`)).toBeNull();
    expect(await val(`userChatRooms/u0/${first}/hidden`)).toBe(true);
    expect(await val(`userChatRooms/u1/${first}/hidden`)).toBe(true);
    expect((await db.doc(`chatPairs/${pairId}`).get()).get("roomStateOwner")).toBeNull();
  });

  it("RTDB 반영이 실패하면 lease 를 풀고 RETRY_LATER, 다시 부르면 끝까지 처리된다", async () => {
    await seed(first);
    const failing = { ref: (path?: string) => path === undefined ? { update: async () => { throw new Error("rtdb down"); } } : rtdb.ref(path) } as unknown as Database;
    await expect(leaver(failing).leave(first, "u0")).rejects.toMatchObject(reason("RETRY_LATER"));
    expect((await db.doc(`chatPairs/${pairId}`).get()).get("roomStateOwner")).toBeNull();
    expect(Object.keys(await val(`chatRooms/${first}/members`)).sort()).toEqual(["u0", "u1"]);
    await leaver().leave(first, "u0");
    expect(Object.keys(await val(`chatRooms/${first}/members`))).toEqual(["u1"]);
  });

  it("나간 뒤 같은 두 사람의 다음 약속은 새 방으로 연결되고, 이전 방은 남은 사람에게 보관 상태로 남는다", async () => {
    await seed(first);
    await leaver().leave(first, "u0");
    await seed(third, "ACTIVE");
    const next = (await db.doc(`matches/${third}`).get()).get("chatRoomId");
    expect(next).toBe(third);
    expect(Object.keys(await val(`chatRooms/${third}/members`)).sort()).toEqual(["u0", "u1"]);
    expect(await val(`userChatRooms/u1/${first}`)).toMatchObject({ hidden: false, archived: true });
    await assertSucceeds(send("u0", third));
  });

  it("같은 두 사람의 새 방에 ACTIVE 약속이 있어도 남은 사람은 이전 보관 방을 나갈 수 있고, 새 방은 그대로다(#121)", async () => {
    await seed(first);
    await leaver().leave(first, "u0");
    await seed(third, "ACTIVE");
    expect((await db.doc(`chatPairs/${pairId}`).get()).get("roomId")).toBe(third);
    const newRoomBefore = { members: await val(`chatRooms/${third}/members`), control: await val(`chatRooms/${third}/control`),
      u0: await val(`userChatRooms/u0/${third}`), u1: await val(`userChatRooms/u1/${third}`) };

    // 이전 방에 연결된 약속은 ENDED 뿐이므로 ACTIVE_MATCH 가 아니다. pair 의 현재 방이 달라도 막지 않는다.
    expect(await leaver().leave(first, "u1")).toEqual({ chatRoomId: first, left: true });

    expect(await val(`chatRooms/${first}/members`)).toBeNull();
    expect(await val(`userChatRooms/u1/${first}/hidden`)).toBe(true);
    expect((await db.doc(`chatRoomLinks/${first}`).get()).get("participantUids")).toEqual([]);

    // 새 방의 멤버·control·양측 목록 항목은 바뀌지 않고, pair 는 계속 새 방을 가리킨다. lease 는 풀려 있다.
    expect(await val(`chatRooms/${third}/members`)).toEqual(newRoomBefore.members);
    expect(await val(`chatRooms/${third}/control`)).toEqual(newRoomBefore.control);
    expect(await val(`userChatRooms/u0/${third}`)).toEqual(newRoomBefore.u0);
    expect(await val(`userChatRooms/u1/${third}`)).toEqual(newRoomBefore.u1);
    expect(newRoomBefore.u1).toMatchObject({ status: "ACTIVE", hidden: false });
    expect((await db.doc(`chatRoomLinks/${third}`).get()).get("participantUids")).toEqual(["u0", "u1"]);
    expect((await db.doc(`chatPairs/${pairId}`).get()).data()).toMatchObject({ roomId: third, roomStateOwner: null, roomStateLeaseUntil: null });
    expect((await db.doc(`matches/${third}`).get()).get("status")).toBe("ACTIVE");

    await assertFails(read("u1", first));
    await assertSucceeds(send("u0", third));
    await assertSucceeds(send("u1", third));
  });
});
