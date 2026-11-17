import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getDatabase, type Database } from "firebase-admin/database";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { beforeAll, beforeEach, afterAll, describe, expect, it, vi } from "vitest";
import { ChatLinkService, chatPairKey } from "../src/chat/link";

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
  it("둘 다 이탈한 방만 삭제하고 새 방은 보존한다", async () => {
    await linker().link(first); await rtdb.ref(`chatRooms/${first}/members`).remove();
    await rtdb.ref(`chatRooms/${first}/messages/old`).set({ senderId: "u0", content: "기록", createdAt: 1 });
    await seed(second); await linker().link(second);
    expect((await rtdb.ref(`chatRooms/${first}`).get()).exists()).toBe(false);
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
    await expect(linker().link(second)).rejects.toMatchObject({ details: { reason: "CHAT_PAIR_BUSY" } });
    clock += 120_000;
    expect((await linker().link(first)).chatReady).toBe(true);
    expect((await linker().link(second)).chatRoomId).toBe(first);
    expect((await db.doc(`chatPairs/${pairId}`).get()).get("pendingMatchId")).toBeNull();
  });
});
