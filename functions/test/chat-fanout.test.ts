import { describe, expect, it } from "vitest";
import { applyMessage, fanOutMessage, parseMessage, type RoomEntry, type RootRef } from "../src/chat/fanout";
import { CHAT_TRIGGER_REGION, chatTriggerRegion } from "../src/chat/trigger";

const MATCH = "a".repeat(64);
const message = (senderId = "me", createdAt = 200, content = "안녕") => ({ senderId, content, createdAt });
const entry = (patch: RoomEntry = {}): RoomEntry => ({
  otherUid: "other", status: "ACTIVE", hidden: false,
  lastMessage: "이전", lastMessageAt: 100, lastMessageId: "-a", unreadCount: 0, ...patch,
});

describe("BT-022 메시지 형식", () => {
  it("필수 필드·길이·공백을 검사한다", () => {
    expect(parseMessage(message())).toEqual(message());
    expect(parseMessage({ ...message(), content: " \n\t" })).toBeNull();
    expect(parseMessage({ ...message(), content: "x".repeat(1001) })).toBeNull();
    expect(parseMessage({ ...message(), createdAt: "200" })).toBeNull();
    expect(parseMessage({ content: "x", createdAt: 1 })).toBeNull();
    expect(parseMessage(null)).toBeNull();
  });
});

describe("BT-022 userChatRooms 갱신", () => {
  it("보낸 사람은 마지막 메시지만, 받는 사람은 안읽음 수도 1 올린다", () => {
    expect(applyMessage(entry(), "me", "-b", message())).toEqual(entry({
      lastMessage: "안녕", lastMessageAt: 200, lastMessageId: "-b",
    }));
    expect(applyMessage(entry({ otherUid: "me", unreadCount: 2 }), "other", "-b", message())).toEqual(entry({
      otherUid: "me", lastMessage: "안녕", lastMessageAt: 200, lastMessageId: "-b", unreadCount: 3,
    }));
  });

  it("같은 메시지를 다시 받으면 건너뛴다(at-least-once 멱등)", () => {
    const applied = applyMessage(entry(), "other", "-b", message()) as RoomEntry;
    expect(applyMessage(applied, "other", "-b", message())).toBeUndefined();
  });

  it("늦게 온 오래된 메시지는 마지막 메시지를 덮지 않는다", () => {
    const current = entry({ lastMessageAt: 300, lastMessageId: "-z" });
    expect(applyMessage(current, "me", "-b", message("me", 200))).toBeUndefined();
    expect(applyMessage(current, "other", "-b", message("me", 200))).toEqual({ ...current, unreadCount: 1 });
  });

  it("같은 시각이면 push 키가 큰 쪽이 마지막 메시지다", () => {
    const current = entry({ lastMessageAt: 200, lastMessageId: "-b" });
    expect(applyMessage(current, "me", "-c", message("me", 200, "나중"))?.lastMessage).toBe("나중");
    expect(applyMessage(current, "me", "-a", message("me", 200, "먼저"))).toBeUndefined();
  });

  it("항목이 없으면 만들지 않고(null), 나간 방 표시는 유지한다", () => {
    expect(applyMessage(null, "me", "-b", message())).toBeNull();
    expect(applyMessage(entry({ hidden: true }), "other", "-b", message())?.hidden).toBe(true);
  });

  it("잘못된 unreadCount 는 0 으로 보고 1 부터 센다", () => {
    expect(applyMessage(entry({ unreadCount: -3 }), "other", "-b", message())?.unreadCount).toBe(1);
    expect(applyMessage(entry({ unreadCount: "x" }), "other", "-b", message())?.unreadCount).toBe(1);
  });
});

/** 메모리 RTDB. 트랜잭션은 첫 호출에 null(로컬 캐시 없음)을 넘기는 실제 SDK 동작을 흉내 낸다. */
function memoryRoot(data: Record<string, unknown>): RootRef & { data: Record<string, unknown> } {
  return {
    data,
    child(path: string) {
      return {
        async get() {
          const value = data[path];
          return { exists: () => value !== undefined, val: () => value };
        },
        async transaction(update: (current: RoomEntry | null) => RoomEntry | null | undefined) {
          let result = update(null);
          const server = (data[path] ?? null) as RoomEntry | null;
          if (result === undefined) return;
          if (server !== null) result = update(server);
          if (result === undefined) return;
          if (result === null) delete data[path]; else data[path] = result;
        },
      };
    },
  };
}

describe("BT-022 fanOutMessage", () => {
  const seed = () => memoryRoot({
    [`chatRooms/${MATCH}/members`]: { me: { joinedAt: 1 }, other: { joinedAt: 1 } },
    [`userChatRooms/me/${MATCH}`]: entry(),
    [`userChatRooms/other/${MATCH}`]: entry({ otherUid: "me" }),
  });

  it("두 멤버 항목을 갱신하고, 첫 호출이 null 이어도 서버 값으로 다시 시도한다", async () => {
    const root = seed();
    await expect(fanOutMessage(root, MATCH, "-b", message())).resolves.toBe("applied");
    expect(root.data[`userChatRooms/me/${MATCH}`]).toMatchObject({ lastMessage: "안녕", unreadCount: 0 });
    expect(root.data[`userChatRooms/other/${MATCH}`]).toMatchObject({ lastMessage: "안녕", unreadCount: 1 });
  });

  it("멤버가 아닌 발신자·형식 오류 메시지는 반영하지 않는다", async () => {
    const root = seed();
    await expect(fanOutMessage(root, MATCH, "-b", message("outsider"))).resolves.toBe("not-member");
    await expect(fanOutMessage(root, MATCH, "-c", { senderId: "me" })).resolves.toBe("invalid-message");
    expect(root.data[`userChatRooms/other/${MATCH}`]).toEqual(entry({ otherUid: "me" }));
  });

  it("항목이 없는 멤버는 만들지 않는다", async () => {
    const root = seed();
    delete root.data[`userChatRooms/other/${MATCH}`];
    await fanOutMessage(root, MATCH, "-b", message());
    expect(root.data[`userChatRooms/other/${MATCH}`]).toBeUndefined();
  });

  it("배포 지역은 RTDB 인스턴스 지역(asia-southeast1), 에뮬레이터에서만 us-central1 이다", () => {
    expect(CHAT_TRIGGER_REGION).toBe("asia-southeast1");
    expect(chatTriggerRegion({})).toBe("asia-southeast1");
    expect(chatTriggerRegion({ FUNCTIONS_EMULATOR: "false" })).toBe("asia-southeast1");
    expect(chatTriggerRegion({ FUNCTIONS_EMULATOR: "true" })).toBe("us-central1");
  });
});
