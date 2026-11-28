import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getDatabase } from "firebase-admin/database";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { FirebaseChatContextAccess } from "../src/ai/chatContext";
import { runAiTask } from "../src/ai/gateway";
import { suggestChatRepliesTask } from "../src/ai/tasks/suggestChatReplies";
import { ChatLinkService } from "../src/chat/link";
import { ctx, expectReason, makeDeps } from "../test/helpers";

/** BT-041: 실제 연결 함수가 만든 방에서 답장 추천이 읽는 범위와 권한을 확인한다. */
const projectId = "demo-bintime-reply", first = "c".repeat(64), second = "d".repeat(64);
const app = initializeApp({ projectId, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? "127.0.0.1:9000"}?ns=${projectId}` }, `reply-${randomUUID()}`);
const db = getFirestore(app), rtdb = getDatabase(app);
const clock = Date.parse("2026-11-24T00:00:00Z");
let env: RulesTestEnvironment;
const access = () => new FirebaseChatContextAccess(db, () => rtdb);
const seed = (id: string, extra: Record<string, unknown> = {}) => db.doc(`matches/${id}`).set({ requestId: id, participantUids: ["u0", "u1"],
  purpose: "카페·디저트", meetingDate: "2026-11-24", startMinute: 600, endMinute: 690, status: "ACTIVE", chatReady: false,
  createdAt: Timestamp.fromMillis(clock), ...extra });
const link = (id: string) => new ChatLinkService(db, rtdb, () => new Date(clock)).link(id);
const post = (room: string, senderId: string, content: string, createdAt: number) => rtdb.ref(`chatRooms/${room}/messages`).push({ senderId, content, createdAt });

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST)("BT-041 답장 추천 채팅 맥락", () => {
  beforeAll(async () => {
    for (const host of [process.env.FIRESTORE_EMULATOR_HOST, process.env.FIREBASE_DATABASE_EMULATOR_HOST]) if (!/^(127\.0\.0\.1|localhost):/.test(host ?? "")) throw new Error("local emulators required");
    // 운영과 같은 RTDB 규칙을 올린다(messages 의 .indexOn createdAt 이 있어야 최근 메시지 쿼리가 된다).
    const [host, port] = process.env.FIREBASE_DATABASE_EMULATOR_HOST!.split(":");
    env = await initializeTestEnvironment({ projectId, firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
      database: { host, port: Number(port), rules: readFileSync(new URL("../../database.rules.json", import.meta.url), "utf8") } });
  });
  beforeEach(async () => {
    await env.clearFirestore();
    await env.clearDatabase();
    await seed(first);
    await link(first);
  });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("재사용된 방의 두 번째 약속은 chatRoomId 의 메시지를 시간순 최근 N개로 읽는다", async () => {
    await seed(second);
    expect((await link(second)).chatRoomId).toBe(first);
    for (let i = 0; i < 5; i++) await post(first, i % 2 ? "u1" : "u0", `m${i}`, clock + i);
    await post(second, "u0", "다른 경로", clock + 9); // matchId 경로는 읽지 않는다
    const room = await access().room(second, "u0");
    expect(room).toEqual({ chatRoomId: first, participantUids: ["u0", "u1"], purpose: "카페·디저트" });
    expect((await access().recentMessages(first, 3)).map((l) => l.content)).toEqual(["m2", "m3", "m4"]);
  });

  it("ENDED 매칭도 현재 멤버면 허용한다", async () => {
    await db.doc(`matches/${first}`).update({ status: "ENDED" });
    await expect(access().room(first, "u1")).resolves.toMatchObject({ chatRoomId: first });
  });

  it("비참여자·없는 매칭·나간 멤버·보관/전송 불가 방은 PERMISSION_DENIED", async () => {
    await expect(access().room(first, "outsider")).rejects.toMatchObject({ reason: "PERMISSION_DENIED" });
    await expect(access().room("e".repeat(64), "u0")).rejects.toMatchObject({ reason: "PERMISSION_DENIED" });
    await rtdb.ref(`chatRooms/${first}/members/u0`).remove();
    await expect(access().room(first, "u0")).rejects.toMatchObject({ reason: "PERMISSION_DENIED" });
    await expect(access().room(first, "u1")).resolves.toBeDefined();
    await rtdb.ref(`chatRooms/${first}/control/archived`).set(true);
    await expect(access().room(first, "u1")).rejects.toMatchObject({ reason: "PERMISSION_DENIED" });
    await rtdb.ref(`chatRooms/${first}/control`).update({ archived: false, writable: false });
    await expect(access().room(first, "u1")).rejects.toMatchObject({ reason: "PERMISSION_DENIED" });
  });

  it("연결 전(chatRoomId 없음) 약속은 AI_UNAVAILABLE(retryable)", async () => {
    await seed(second);
    await expect(access().room(second, "u0")).rejects.toMatchObject({ reason: "AI_UNAVAILABLE", retryable: true });
  });

  it("live 게이트웨이: 이름·UID·연락처 없이 나/상대 대화와 목적만 모델에 보낸다", async () => {
    await post(first, "u1", "안녕하세요! 제 메일은 test.user@kookmin.ac.kr 이에요", clock + 1);
    await post(first, "u0", "반가워요", clock + 2);
    const deps = { ...makeDeps({ mode: "live", matches: { [first]: ["u0", "u1"] }, steps: [{ text: '{"replies":["좋아요","몇 시에 볼까요?","어디서 볼까요?"]}' }] }), chatContext: access() };
    const res = await runAiTask(suggestChatRepliesTask, { matchId: first }, ctx("u0"), deps);
    expect(res.data.replies).toHaveLength(3);
    const contents = deps.client.calls[0].contents as string;
    expect(JSON.parse(contents)).toEqual({ purpose: "카페·디저트",
      messages: [{ from: "상대", text: "안녕하세요! 제 메일은 이에요" }, { from: "나", text: "반가워요" }] });
    for (const hidden of ["u0", "u1", first, "kookmin.ac.kr", String(clock)]) expect(contents).not.toContain(hidden);
    await rtdb.ref(`chatRooms/${first}/members/u0`).remove();
    await expectReason(runAiTask(suggestChatRepliesTask, { matchId: first }, ctx("u0"), deps), "PERMISSION_DENIED");
  });
});
