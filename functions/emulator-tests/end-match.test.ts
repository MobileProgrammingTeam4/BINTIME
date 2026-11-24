import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getDatabase } from "firebase-admin/database";
import { initializeTestEnvironment, assertFails, assertSucceeds, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, updateDoc } from "firebase/firestore";
import { beforeAll, beforeEach, afterAll, describe, it, expect, vi } from "vitest";
import { ChatLinkService } from "../src/chat/link";
import { RoomStateService } from "../src/chat/roomState";
import { MatchEndService } from "../src/requests/endMatch";
import { createEndMatchCallable } from "../src/requests/endCallable";

const projectId = "demo-bintime-end", id = "a".repeat(64), other = "b".repeat(64);
const app = initializeApp({ projectId, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? "127.0.0.1:9000"}?ns=${projectId}` }, randomUUID());
const db = getFirestore(app), rtdb = getDatabase(app);
let clock = Date.parse("2026-11-24T10:30:00+09:00"), env: RulesTestEnvironment;
const now = () => new Date(clock), rooms = () => new RoomStateService(db, rtdb, now);
const service = () => new MatchEndService(db, rooms(), now);
const seed = (key = id, extra = {}) => db.doc(`matches/${key}`).set({ participantUids: ["a", "b"], status: "ACTIVE",
  meetingDate: "2026-11-24", startMinute: 600, endMinute: 660, requestId: key, chatReady: false, ...extra });
describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST)("BT-038 end, room aggregation and permissions", () => {
  beforeAll(async () => {
    for (const host of [process.env.FIRESTORE_EMULATOR_HOST, process.env.FIREBASE_DATABASE_EMULATOR_HOST]) if (!/^(127\.0\.0\.1|localhost):/.test(host ?? "")) throw new Error("local emulators required");
    const [host, port] = process.env.FIREBASE_DATABASE_EMULATOR_HOST!.split(":");
    env = await initializeTestEnvironment({ projectId, firestore: { rules: readFileSync("../firestore.rules", "utf8") },
      database: { host, port: Number(port), rules: readFileSync("../database.rules.json", "utf8") } });
  });
  beforeEach(async () => { vi.restoreAllMocks(); await env.clearFirestore(); await env.clearDatabase(); clock = Date.parse("2026-11-24T10:30:00+09:00"); await seed(); });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });
  it("only a participant ends; clients cannot write end state or repair jobs", async () => {
    await expect(service().end(id, "outsider")).rejects.toMatchObject({ code: "permission-denied" });
    const client = env.authenticatedContext("a").firestore();
    await assertFails(updateDoc(doc(client, "matches", id), { status: "ENDED" }));
    await assertFails(getDoc(doc(client, "roomStateJobs", id)));
    await expect(service().end(id, "a")).resolves.toMatchObject({ status: "ENDED" });
  });
  it("concurrent/duplicate manual ending preserves its first decision and immutable snapshot", async () => {
    clock = Date.parse("2026-11-24T09:30:00+09:00");
    await Promise.all([service().end(id, "a"), service().end(id, "b")]);
    const before = (await db.doc(`matches/${id}`).get()).data();
    clock += 3_600_000; await service().end(id, "a");
    expect((await db.doc(`matches/${id}`).get()).data()).toEqual(before);
    expect(before).toMatchObject({ reviewEligible: false, endReason: "MANUAL", startMinute: 600, endMinute: 660, requestId: id });
  });
  it("ending preserves messages, unread counters and actual member send permission", async () => {
    await new ChatLinkService(db, rtdb, now).link(id);
    await rtdb.ref(`userChatRooms/b/${id}/unreadCount`).set(4);
    await service().end(id, "a");
    expect((await rtdb.ref(`userChatRooms/a/${id}/status`).get()).val()).toBe("ENDED");
    expect((await rtdb.ref(`userChatRooms/b/${id}/unreadCount`).get()).val()).toBe(4);
    const message = { senderId: "a", content: "종료 후 대화", createdAt: { ".sv": "timestamp" } };
    await assertSucceeds(env.authenticatedContext("a").database().ref(`chatRooms/${id}/messages/after`).set(message));
    await assertFails(env.authenticatedContext("outsider").database().ref(`chatRooms/${id}/messages/other`).set({ ...message, senderId: "outsider" }));
    expect((await rtdb.ref(`chatRooms/${id}/control/writable`).get()).val()).toBe(true);
  });
  it("one future active appointment keeps a reused room ACTIVE", async () => {
    const linker = new ChatLinkService(db, rtdb, now); await linker.link(id);
    await seed(other, { meetingDate: "2026-11-25" }); await linker.link(other);
    await service().end(id, "a");
    expect((await rtdb.ref(`userChatRooms/a/${id}/status`).get()).val()).toBe("ACTIVE");
    await service().end(other, "b");
    expect((await rtdb.ref(`userChatRooms/a/${id}/status`).get()).val()).toBe("ENDED");
    expect((await db.doc(`matches/${other}`).get()).get("reviewEligible")).toBe(false);
  });
  it("ENDING before chat linking does not resurrect ACTIVE", async () => {
    await service().end(id, "a");
    await new ChatLinkService(db, rtdb, now).link(id);
    expect((await rtdb.ref(`userChatRooms/a/${id}/status`).get()).val()).toBe("ENDED");
  });
  it("RTDB failure leaves a repair job; retry does not undo the completed end", async () => {
    await new ChatLinkService(db, rtdb, now).link(id);
    const mirror = rooms(); vi.spyOn(mirror, "sync").mockRejectedValueOnce(new Error("offline"));
    await new MatchEndService(db, mirror, now).end(id, "a");
    expect((await db.doc(`matches/${id}`).get()).get("status")).toBe("ENDED");
    expect((await db.doc(`roomStateJobs/${id}`).get()).get("status")).toBe("PENDING");
    await rooms().retryBatch();
    expect((await rtdb.ref(`userChatRooms/a/${id}/status`).get()).val()).toBe("ENDED");
  });
  it("scheduled ending handles old dates, exact boundary and leaves future appointments", async () => {
    await seed(id, { meetingDate: "2026-11-23" }); await seed(other, { endMinute: 630 }); await seed("c".repeat(64), { endMinute: 631 });
    expect(await service().runBatch()).toMatchObject({ ended: 2, failed: 0 });
    expect((await db.doc(`matches/${"c".repeat(64)}`).get()).get("status")).toBe("ACTIVE");
  });
  it("Callable refuses unauthenticated users and client supplied ownership fields", async () => {
    const fn = createEndMatchCallable(service);
    await expect(fn.run({ data: { matchId: id } } as never)).rejects.toMatchObject({ code: "unauthenticated" });
    await expect(fn.run({ auth: { uid: "a", token: { email: "test@school.ac.kr", email_verified: true } }, data: { matchId: id, uid: "b" } } as never)).rejects.toBeDefined();
  });
});
