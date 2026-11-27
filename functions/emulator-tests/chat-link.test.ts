import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getDatabase, type Database } from "firebase-admin/database";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { beforeAll, beforeEach, afterAll, describe, expect, it, vi } from "vitest";
import { ChatLinkService, chatPairKey } from "../src/chat/link";
import { RoomStateService } from "../src/chat/roomState";

const projectId = "demo-bintime-link", first = "a".repeat(64), second = "b".repeat(64);
const app = initializeApp({ projectId, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? "127.0.0.1:9000"}?ns=${projectId}` }, `link-${randomUUID()}`);
const db = getFirestore(app), rtdb = getDatabase(app);
let clock = Date.parse("2026-11-17T00:00:00Z"), env: RulesTestEnvironment;
const linker = () => new ChatLinkService(db, rtdb, () => new Date(clock));
const seed = async (id: string) => db.doc(`matches/${id}`).set({ requestId: id, participantUids: ["u0", "u1"], purpose: "스터디",
  meetingDate: "2026-11-17", startMinute: 600, endMinute: 690, status: "ACTIVE", chatReady: false, createdAt: Timestamp.fromMillis(clock) });
const message = (uid: string) => ({ senderId: uid, content: "안녕하세요", createdAt: { ".sv": "timestamp" } });

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST)("BT-033 방 연결·복구·권한", () => {
  beforeAll(async () => {
    for (const host of [process.env.FIRESTORE_EMULATOR_HOST, process.env.FIREBASE_DATABASE_EMULATOR_HOST]) if (!/^(127\.0\.0\.1|localhost):/.test(host ?? "")) throw new Error("local emulators required");
    const [host, port] = process.env.FIREBASE_DATABASE_EMULATOR_HOST!.split(":");
    env = await initializeTestEnvironment({ projectId, firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
      database: { host, port: Number(port), rules: readFileSync(new URL("../../database.rules.json", import.meta.url), "utf8") } });
  });
  beforeEach(async () => { vi.restoreAllMocks(); clock = Date.parse("2026-11-17T00:00:00Z"); await env.clearFirestore(); await env.clearDatabase(); await seed(first); });
  afterAll(async () => { vi.restoreAllMocks(); await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("참여자만 연결하며 재시도·동시 실행에도 한 방과 불변 스냅샷을 만든다", async () => {
    await expect(linker().link(first, "outsider")).rejects.toMatchObject({ code: "permission-denied" });
    const results = await Promise.allSettled([linker().link(first, "u0"), linker().link(first, "u1")]);
    expect(results.some(r => r.status === "fulfilled")).toBe(true);
    expect(await linker().link(first, "u0")).toEqual({ matchId: first, chatRoomId: first, chatReady: true });
    expect((await rtdb.ref("chatRooms").get()).numChildren()).toBe(1);
    expect((await db.doc(`matches/${first}`).get()).data()).toMatchObject({ startMinute: 600, endMinute: 690, chatRoomId: first, chatReady: true });
    for (const uid of ["u0", "u1"]) await assertSucceeds(env.authenticatedContext(uid).database().ref(`chatRooms/${first}/messages/${uid}`).set(message(uid)));
    const client = env.authenticatedContext("u0").firestore();
    await assertFails(getDoc(doc(client, "chatPairs", chatPairKey(["u0", "u1"]))));
    await assertFails(getDoc(doc(client, "chatLinkJobs", first)));
    await assertFails(setDoc(doc(client, "chatRoomLinks", first), { chatReady: false }));
  });
  it("linkedAt은 새 목록 항목을 만들 때 한 번만 기록하고 재시도·재사용·메시지 반영에도 바꾸지 않는다", async () => {
    const createdAt = clock;
    await linker().link(first);
    for (const uid of ["u0", "u1"]) expect((await rtdb.ref(`userChatRooms/${uid}/${first}/linkedAt`).get()).val()).toBe(createdAt);
    // 재시도(READY 이후 재호출)·작업 중단 뒤 재실행 모두 같은 값
    clock += 60_000;
    await linker().link(first, "u0");
    await db.doc(`chatLinkJobs/${first}`).update({ status: "FAILED", nextAttemptAt: null });
    await linker().link(first, "u1");
    expect((await rtdb.ref(`userChatRooms/u0/${first}/linkedAt`).get()).val()).toBe(createdAt);
    // 기존 방 재사용: 안읽음·최근 메시지 유지, linkedAt 그대로
    await rtdb.ref(`userChatRooms/u1/${first}`).update({ unreadCount: 3, lastMessage: "기존 대화", lastMessageAt: createdAt + 1 });
    clock += 60_000; await seed(second);
    expect((await linker().link(second)).chatRoomId).toBe(first);
    expect((await rtdb.ref(`userChatRooms/u1/${first}`).get()).val()).toMatchObject({ linkedAt: createdAt, unreadCount: 3, lastMessage: "기존 대화", lastMessageAt: createdAt + 1 });
    // linkedAt은 서버 전용: 앱이 쓸 수 없다
    await assertFails(env.authenticatedContext("u0").database().ref(`userChatRooms/u0/${first}/linkedAt`).set(0));
  });
  it("linkedAt 없는 기존 항목은 재연결해도 새로 만들지 않는다(필드 없는 방 호환)", async () => {
    await linker().link(first);
    await rtdb.ref(`userChatRooms/u0/${first}/linkedAt`).remove();
    clock += 60_000; await seed(second);
    await linker().link(second);
    expect((await rtdb.ref(`userChatRooms/u0/${first}/linkedAt`).get()).exists()).toBe(false);
    expect((await rtdb.ref(`userChatRooms/u0/${first}/otherUid`).get()).val()).toBe("u1");
  });
  it("양측 잔류 방은 재사용하고 안읽음·메시지·과거 약속을 초기화하지 않는다", async () => {
    await linker().link(first); await db.doc(`matches/${first}`).update({ status: "ENDED" });
    await rtdb.ref(`chatRooms/${first}/messages/old`).set({ senderId: "u0", content: "기존 대화", createdAt: 1 });
    await rtdb.ref(`userChatRooms/u1/${first}`).update({ unreadCount: 7, lastMessage: "기존 대화", lastMessageAt: 1 });
    await seed(second);
    expect((await linker().link(second)).chatRoomId).toBe(first);
    expect((await rtdb.ref(`userChatRooms/u1/${first}/unreadCount`).get()).val()).toBe(7);
    expect((await rtdb.ref(`chatRooms/${first}/messages/old/content`).get()).val()).toBe("기존 대화");
    expect((await db.doc(`matches/${first}`).get()).get("status")).toBe("ENDED");
    expect((await rtdb.ref("chatRooms").get()).numChildren()).toBe(1);
  });
  it.each(["failed", "leased"])("집계가 %s여도 재사용 방의 새 ACTIVE 약속을 즉시 목록에 반영한다", async mode => {
    await linker().link(first);
    await db.doc(`matches/${first}`).update({ status: "ENDED" });
    await new RoomStateService(db, rtdb, () => new Date(clock)).sync(first, ["u0", "u1"]);
    await rtdb.ref(`userChatRooms/u1/${first}`).update({ unreadCount: 7, lastMessage: "보존할 대화" });
    await seed(second);
    const sync = vi.spyOn(RoomStateService.prototype, "sync");
    if (mode === "failed") sync.mockRejectedValueOnce(new Error("offline"));
    else sync.mockResolvedValueOnce(false);
    expect((await linker().link(second)).chatRoomId).toBe(first);
    for (const uid of ["u0", "u1"]) expect((await rtdb.ref(`userChatRooms/${uid}/${first}/status`).get()).val()).toBe("ACTIVE");
    expect((await rtdb.ref(`userChatRooms/u1/${first}`).get()).val()).toMatchObject({ unreadCount: 7, lastMessage: "보존할 대화" });
    expect((await db.doc(`roomStateJobs/${first}`).get()).get("status")).toBe("PENDING");
  });
  it("연결 전에 종료된 약속은 집계 실패 시에도 ACTIVE로 되살리지 않는다", async () => {
    await db.doc(`matches/${first}`).update({ status: "ENDED" });
    vi.spyOn(RoomStateService.prototype, "sync").mockRejectedValueOnce(new Error("offline"));
    await linker().link(first);
    for (const uid of ["u0", "u1"]) expect((await rtdb.ref(`userChatRooms/${uid}/${first}/status`).get()).val()).toBe("ENDED");
  });
  it("한 명 이탈은 이전 방을 읽기 전용 보관하고 새 방에 과거 기록을 공개하지 않는다", async () => {
    await linker().link(first);
    await rtdb.ref(`chatRooms/${first}/messages/old`).set({ senderId: "u0", content: "과거 대화", createdAt: 1 });
    await rtdb.ref(`chatRooms/${first}/members/u1`).remove();
    await rtdb.ref(`userChatRooms/u1/${first}/hidden`).set(true);
    await seed(second); expect((await linker().link(second)).chatRoomId).toBe(second);
    const remaining = env.authenticatedContext("u0").database(), left = env.authenticatedContext("u1").database();
    await assertSucceeds(remaining.ref(`chatRooms/${first}/messages`).get());
    await assertFails(remaining.ref(`chatRooms/${first}/messages/new`).set(message("u0")));
    await assertFails(left.ref(`chatRooms/${first}/messages`).get());
    await assertFails(left.ref(`chatRooms/${first}/messages/new`).set(message("u1")));
    await assertSucceeds(left.ref(`chatRooms/${second}/messages/new`).set(message("u1")));
    expect((await rtdb.ref(`chatRooms/${second}/messages/old`).get()).exists()).toBe(false);
    expect((await rtdb.ref(`userChatRooms/u0/${first}/archived`).get()).val()).toBe(true);
    expect((await db.doc(`chatRoomLinks/${first}`).get()).get("chatReady")).toBe(false);
    expect((await linker().link(first)).chatReady).toBe(false); // 오래된 이벤트로 재입장시키지 않는다.
  });
  it("둘 다 이탈한 방은 서버에만 보관하고 양측의 목록·과거 대화 접근을 막는다", async () => {
    await linker().link(first); await rtdb.ref(`chatRooms/${first}/members`).remove();
    await rtdb.ref(`chatRooms/${first}/messages/old`).set({ senderId: "u0", content: "기록", createdAt: 1 });
    await seed(second); await linker().link(second);
    expect((await rtdb.ref(`chatRooms/${first}/messages/old/content`).get()).val()).toBe("기록");
    expect((await rtdb.ref(`chatRooms/${first}/control`).get()).val()).toMatchObject({ archived: true, writable: false, chatReady: false });
    for (const uid of ["u0", "u1"]) {
      expect((await rtdb.ref(`userChatRooms/${uid}/${first}/hidden`).get()).val()).toBe(true);
      await assertFails(env.authenticatedContext(uid).database().ref(`chatRooms/${first}/messages`).get());
      await assertFails(env.authenticatedContext(uid).database().ref(`chatRooms/${first}/messages/new`).set(message(uid)));
      await assertFails(getDoc(doc(env.authenticatedContext(uid).firestore(), "chatRoomLinks", first)));
    }
    expect((await rtdb.ref(`chatRooms/${second}/members`).get()).numChildren()).toBe(2);
  });
  it("한쪽 목록 생성 실패 후 같은 계획으로 복구하고 준비 전 전송은 거부한다", async () => {
    let fail = true;
    const partial = { ref(path?: string) { if (path === `userChatRooms/u1/${first}` && fail) { fail = false; throw new Error("injected RTDB failure"); } return rtdb.ref(path); } } as Database;
    await expect(new ChatLinkService(db, partial, () => new Date(clock)).link(first)).rejects.toThrow("injected RTDB failure");
    expect((await db.doc(`matches/${first}`).get()).get("chatReady")).toBe(false);
    await assertFails(env.authenticatedContext("u0").database().ref(`chatRooms/${first}/messages/early`).set(message("u0")));
    await expect(linker().link(first)).rejects.toMatchObject({ details: { reason: "CHAT_RETRY_LATER" } });
    clock += 10_000; expect((await linker().link(first)).chatReady).toBe(true);
    expect((await rtdb.ref("chatRooms").get()).numChildren()).toBe(1);
  });
  it.each(["control", "members"])("초기 %s 생성 실패 시 전송 권한을 남기지 않고 재시도로 복구한다", async phase => {
    let fail = true;
    const partial = { ref(path?: string) {
      if (path === `chatRooms/${first}/${phase}` && fail) { fail = false; throw new Error("injected initial failure"); }
      return rtdb.ref(path);
    } } as Database;
    await expect(new ChatLinkService(db, partial, () => new Date(clock)).link(first)).rejects.toThrow("injected initial failure");
    expect((await db.doc(`matches/${first}`).get()).get("chatReady")).toBe(false);
    expect((await db.doc(`chatLinkJobs/${first}`).get()).get("status")).toBe("FAILED");
    expect((await rtdb.ref(`chatRooms/${first}/members`).get()).exists()).toBe(false);
    await assertFails(env.authenticatedContext("u0").database().ref(`chatRooms/${first}/messages/early`).set(message("u0")));
    clock += 10_000;
    expect((await linker().link(first)).chatReady).toBe(true);
    expect((await rtdb.ref("chatRooms").get()).numChildren()).toBe(1);
  });
  it("RTDB 성공 후 Firestore 완료 실패를 재시도해도 메시지·안읽음 수를 보존한다", async () => {
    const original = db.runTransaction.bind(db); let calls = 0;
    const spy = vi.spyOn(db, "runTransaction").mockImplementation((...args: Parameters<typeof db.runTransaction>) => {
      if (++calls === 3) return Promise.reject(new Error("injected finalize failure"));
      return original(...args);
    });
    await expect(linker().link(first)).rejects.toThrow("injected finalize failure"); spy.mockRestore();
    await rtdb.ref(`userChatRooms/u1/${first}/unreadCount`).set(3);
    await rtdb.ref(`chatRooms/${first}/messages/preserved`).set({ senderId: "u0", content: "보존", createdAt: 1 });
    clock += 10_000; expect((await linker().link(first)).chatReady).toBe(true);
    expect((await rtdb.ref(`userChatRooms/u1/${first}/unreadCount`).get()).val()).toBe(3);
    expect((await rtdb.ref(`chatRooms/${first}/messages/preserved`).get()).exists()).toBe(true);
  });
  it("종료된 프로세스의 lease 이후 복구하며 준비 중 나가기/다른 연결의 공통 잠금을 유지한다", async () => {
    const pairId = chatPairKey(["u0", "u1"]);
    await db.doc(`chatPairs/${pairId}`).set({ participantUids: ["u0", "u1"], pendingMatchId: first });
    await db.doc(`chatLinkJobs/${first}`).set({ status: "PREPARING", workerId: "terminated", leaseUntil: Timestamp.fromMillis(clock + 120_000) });
    await seed(second);
    await expect(linker().link(first)).rejects.toMatchObject({ details: { reason: "CHAT_LINK_BUSY" } });
    await expect(linker().link(second)).rejects.toMatchObject({ details: { reason: "CHAT_PAIR_BUSY", blockingMatchId: first } });
    clock += 120_000;
    expect((await linker().link(first)).chatReady).toBe(true);
    expect((await linker().link(second)).chatRoomId).toBe(first);
    expect((await db.doc(`chatPairs/${pairId}`).get()).get("pendingMatchId")).toBeNull();
  });
  it("실패 작업이 막은 다음 매칭에는 복구할 ID를 제공하고 기존 작업 복구 후 연결한다", async () => {
    const pairId = chatPairKey(["u0", "u1"]);
    await db.doc(`chatPairs/${pairId}`).set({ participantUids: ["u0", "u1"], pendingMatchId: first });
    await db.doc(`chatLinkJobs/${first}`).set({ status: "FAILED", nextAttemptAt: Timestamp.fromMillis(clock - 1) });
    await seed(second);
    await expect(linker().link(second, "outsider")).rejects.toMatchObject({ code: "permission-denied" });
    await expect(linker().link(second, "u0")).rejects.toMatchObject({ details: { reason: "CHAT_PAIR_BUSY", blockingMatchId: first } });
    expect((await db.doc(`chatPairs/${pairId}`).get()).get("pendingMatchId")).toBe(first);
    await linker().link(first, "u0");
    expect((await linker().link(second, "u0")).chatRoomId).toBe(first);
  });
});
