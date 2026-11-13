import { randomUUID } from "node:crypto";
import { deleteApp, initializeApp, type App } from "firebase-admin/app";
import { getDatabase, type Database } from "firebase-admin/database";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { applyMessage, fanOutMessage, type RootRef } from "../src/chat/fanout";

/**
 * BT-022 메시지 트리거 본문(fanOutMessage)을 실제 Admin SDK 트랜잭션으로 검증한다. 로컬 RTDB 에뮬레이터 전용.
 *   firebase emulators:exec --project demo-bintime --only database "npm --prefix functions run test:emulator"
 * 규칙 테스트와 섞이지 않도록 별도 네임스페이스를 쓴다.
 */
const MATCH = "c".repeat(64);
const emulatorHost = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
let app: App;
let db: Database;
const root = () => db.ref() as unknown as RootRef;
const message = (senderId: string, createdAt: number, content = `msg-${createdAt}`) => ({ senderId, content, createdAt });

describe.skipIf(!emulatorHost)("BT-022 채팅 트리거 fan-out (RTDB 에뮬레이터)", () => {
  beforeAll(() => {
    if (!emulatorHost?.startsWith("127.0.0.1:") && !emulatorHost?.startsWith("localhost:")) {
      throw new Error("local database emulator required");
    }
    app = initializeApp({ projectId: "demo-bintime", databaseURL: `http://${emulatorHost}?ns=demo-bintime-fanout` },
      `chat-fanout-${randomUUID()}`);
    db = getDatabase(app);
  });

  afterAll(async () => {
    if (app) await deleteApp(app);
  });

  beforeEach(async () => {
    const room = (otherUid: string) => ({ otherUid, status: "ACTIVE", hidden: false, unreadCount: 0 });
    await db.ref().set({
      chatRooms: { [MATCH]: { members: { me: { joinedAt: 1 }, other: { joinedAt: 1 } } } },
      userChatRooms: { me: { [MATCH]: room("other") }, other: { [MATCH]: room("me") } },
    });
  });

  const entry = async (uid: string) => (await db.ref(`userChatRooms/${uid}/${MATCH}`).get()).val();

  it("동시에 온 메시지 5개를 빠짐없이 세고 마지막 메시지는 가장 늦은 것이다", async () => {
    await Promise.all([1, 2, 3, 4, 5].map((i) => fanOutMessage(root(), MATCH, `-m${i}`, message("me", 1000 + i))));
    expect(await entry("other")).toMatchObject({ unreadCount: 5, lastMessage: "msg-1005", lastMessageId: "-m5" });
    expect(await entry("me")).toMatchObject({ unreadCount: 0, lastMessage: "msg-1005", lastMessageAt: 1005 });
  });

  it("같은 이벤트가 다시 와도 안읽음 수가 늘지 않는다", async () => {
    await fanOutMessage(root(), MATCH, "-m1", message("me", 1000));
    await fanOutMessage(root(), MATCH, "-m1", message("me", 1000));
    expect((await entry("other")).unreadCount).toBe(1);
  });

  it("상대가 읽음 처리(unreadCount = 0)한 뒤 새 메시지는 1부터 센다", async () => {
    await fanOutMessage(root(), MATCH, "-m1", message("me", 1000));
    await db.ref(`userChatRooms/other/${MATCH}/unreadCount`).set(0);
    await fanOutMessage(root(), MATCH, "-m2", message("me", 2000));
    expect(await entry("other")).toMatchObject({ unreadCount: 1, lastMessage: "msg-2000", otherUid: "me" });
  });

  it("[리뷰 2] 서로 다른 메시지 사이에 끼어든 중복 이벤트: m1 → m2 → m1 은 2로 센다", async () => {
    await fanOutMessage(root(), MATCH, "-m1", message("me", 1000));
    await fanOutMessage(root(), MATCH, "-m2", message("me", 2000));
    await fanOutMessage(root(), MATCH, "-m1", message("me", 1000));
    expect(await entry("other")).toMatchObject({ unreadCount: 2, lastMessageId: "-m2" });
  });

  it("[리뷰 2] 오래된 이벤트가 동시에 여러 번 반복돼도 한 번만 센다", async () => {
    for (const [id, at] of [["-m1", 1000], ["-m2", 2000], ["-m3", 3000]] as const) {
      await fanOutMessage(root(), MATCH, id, message("me", at));
    }
    await Promise.all([1, 2, 3, 4].map(() => fanOutMessage(root(), MATCH, "-m1", message("me", 1000))));
    expect(await entry("other")).toMatchObject({ unreadCount: 3, lastMessageId: "-m3" });
  });

  it("[리뷰 2] 부분 실패 후 재처리: 받는 사람만 반영된 상태에서 이벤트를 다시 처리해도 다시 세지 않는다", async () => {
    // 첫 처리에서 받는 사람 항목만 반영되고 보낸 사람 쪽은 실패한 상황
    await db.ref(`userChatRooms/other/${MATCH}`).transaction((current) =>
      applyMessage(current, "other", "-m1", message("me", 1000)));
    expect((await entry("me")).lastMessage).toBeUndefined();
    await fanOutMessage(root(), MATCH, "-m1", message("me", 1000));
    await fanOutMessage(root(), MATCH, "-m1", message("me", 1000));
    expect(await entry("other")).toMatchObject({ unreadCount: 1, lastMessage: "msg-1000" });
    expect(await entry("me")).toMatchObject({ unreadCount: 0, lastMessage: "msg-1000" });
  });

  it("userChatRooms 항목이 없는 멤버에게는 항목을 만들지 않는다", async () => {
    await db.ref(`userChatRooms/other/${MATCH}`).remove();
    await fanOutMessage(root(), MATCH, "-m1", message("me", 1000));
    expect(await entry("other")).toBeNull();
    expect((await entry("me")).lastMessage).toBe("msg-1000");
  });
});
