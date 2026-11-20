import { describe, expect, it, vi } from "vitest";
import { applyMessage, fanOutMessage, parseMessage, RECENT_MESSAGE_LIMIT, type RoomEntry, type RootRef } from "../src/chat/fanout";
import {
  CHAT_TRIGGER_REGION, chatTriggerRegion, createMessageTrigger, handleMessageEvent, MAX_EVENT_AGE_MS,
} from "../src/chat/trigger";

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

/** recentMessageIds 를 뺀 나머지 필드. */
const fields = (value: RoomEntry | null | undefined) => {
  const rest: RoomEntry = { ...(value ?? {}) };
  delete rest.recentMessageIds;
  return rest;
};

describe("BT-022 userChatRooms 갱신", () => {
  it("보낸 사람은 마지막 메시지만, 받는 사람은 안읽음 수도 1 올리고 둘 다 처리 기록을 남긴다", () => {
    const sender = applyMessage(entry(), "me", "-b", message());
    expect(fields(sender)).toEqual(entry({ lastMessage: "안녕", lastMessageAt: 200, lastMessageId: "-b" }));
    expect(sender?.recentMessageIds).toEqual({ "-b": 200 });
    const receiver = applyMessage(entry({ otherUid: "me", unreadCount: 2 }), "other", "-b", message());
    expect(fields(receiver)).toEqual(entry({
      otherUid: "me", lastMessage: "안녕", lastMessageAt: 200, lastMessageId: "-b", unreadCount: 3,
    }));
    expect(receiver?.recentMessageIds).toEqual({ "-b": 200 });
  });

  it("같은 메시지를 다시 받으면 건너뛴다(at-least-once 멱등)", () => {
    const applied = applyMessage(entry(), "other", "-b", message()) as RoomEntry;
    expect(applyMessage(applied, "other", "-b", message())).toBeUndefined();
  });

  it("recentMessageIds 가 없는 기존 항목도 lastMessageId 와 같으면 건너뛴다", () => {
    expect(applyMessage(entry({ lastMessageId: "-b" }), "other", "-b", message())).toBeUndefined();
  });

  it("늦게 온 오래된 메시지는 마지막 메시지를 덮지 않는다", () => {
    const current = entry({ lastMessageAt: 300, lastMessageId: "-z" });
    expect(fields(applyMessage(current, "me", "-b", message("me", 200)))).toEqual(current);
    expect(fields(applyMessage(current, "other", "-b", message("me", 200)))).toEqual({ ...current, unreadCount: 1 });
  });

  it("같은 시각이면 push 키가 큰 쪽이 마지막 메시지다", () => {
    const current = entry({ lastMessageAt: 200, lastMessageId: "-b" });
    expect(applyMessage(current, "me", "-c", message("me", 200, "나중"))?.lastMessage).toBe("나중");
    expect(applyMessage(current, "me", "-a", message("me", 200, "먼저"))?.lastMessage).toBe("이전");
  });

  it("항목이 없으면 만들지 않고(null), 나간 방 표시는 유지한다", () => {
    expect(applyMessage(null, "me", "-b", message())).toBeNull();
    expect(applyMessage(entry({ hidden: true }), "other", "-b", message())?.hidden).toBe(true);
  });

  it("linkedAt·chatReady·archived 등 연결 함수가 쓴 필드는 그대로 둔다(BT-034)", () => {
    const next = applyMessage(entry({ linkedAt: 50, chatReady: true, archived: false }), "other", "-b", message());
    expect(next).toMatchObject({ linkedAt: 50, chatReady: true, archived: false, lastMessageAt: 200, unreadCount: 1 });
  });
  it("잘못된 unreadCount 는 0 으로 보고 1 부터 센다", () => {
    expect(applyMessage(entry({ unreadCount: -3 }), "other", "-b", message())?.unreadCount).toBe(1);
    expect(applyMessage(entry({ unreadCount: "x" }), "other", "-b", message())?.unreadCount).toBe(1);
  });
});

/** 받는 사람 항목에 이벤트를 차례로 적용한다(건너뛰면 값 유지). */
const replay = (start: RoomEntry, events: Array<[string, number]>) =>
  events.reduce<RoomEntry>((current, [id, at]) => applyMessage(current, "other", id, message("me", at, id)) ?? current, start);

describe("BT-022 중복 이벤트 회귀 (PR #96 리뷰 2)", () => {
  it("서로 다른 메시지 사이에 끼어든 중복 이벤트를 다시 세지 않는다: m1 → m2 → m1", () => {
    const result = replay(entry({ otherUid: "me" }), [["-m1", 1000], ["-m2", 2000], ["-m1", 1000]]);
    expect(result.unreadCount).toBe(2);
    expect(result.lastMessageId).toBe("-m2");
  });

  it("오래된 이벤트가 여러 번 반복돼도 한 번만 센다", () => {
    const result = replay(entry({ otherUid: "me" }),
      [["-m1", 1000], ["-m2", 2000], ["-m3", 3000], ["-m1", 1000], ["-m1", 1000], ["-m2", 2000], ["-m1", 1000]]);
    expect(result.unreadCount).toBe(3);
    expect(result.lastMessageId).toBe("-m3");
  });

  it("처리 기록은 최근 RECENT_MESSAGE_LIMIT 개만 남기고, 그보다 오래된 재전달도 세지 않는다", () => {
    const events: Array<[string, number]> = Array.from({ length: RECENT_MESSAGE_LIMIT + 5 },
      (_, i) => [`-m${String(i).padStart(3, "0")}`, 1000 + i]);
    const result = replay(entry({ otherUid: "me" }), events);
    expect(Object.keys(result.recentMessageIds as object)).toHaveLength(RECENT_MESSAGE_LIMIT);
    expect(result.unreadCount).toBe(RECENT_MESSAGE_LIMIT + 5);
    const again = replay(result, [["-m000", 1000], ["-m003", 1003], ["-m010", 1010]]);
    expect(again.unreadCount).toBe(RECENT_MESSAGE_LIMIT + 5);
  });
});

/**
 * 메모리 RTDB. 트랜잭션은 첫 호출에 null(로컬 캐시 없음)을 넘기는 실제 SDK 동작을 흉내 낸다.
 * failOnce 에 넣은 경로는 다음 트랜잭션 한 번을 실패시킨다(부분 실패 재현).
 */
function memoryRoot(data: Record<string, unknown>): RootRef & { data: Record<string, unknown>; failOnce: Set<string> } {
  const failOnce = new Set<string>();
  return {
    data,
    failOnce,
    child(path: string) {
      return {
        async get() {
          const value = data[path];
          return { exists: () => value !== undefined, val: () => value };
        },
        async transaction(update: (current: RoomEntry | null) => RoomEntry | null | undefined) {
          if (failOnce.delete(path)) throw new Error(`transaction failed: ${path}`);
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

  it.each(["me", "other"])("부분 실패 후 재처리(PR #96 리뷰 2): %s 쪽만 실패해도 이미 반영된 쪽은 다시 세지 않는다", async (failed) => {
    const root = seed();
    root.failOnce.add(`userChatRooms/${failed}/${MATCH}`);
    await expect(fanOutMessage(root, MATCH, "-b", message())).rejects.toThrow("transaction failed");
    // 이벤트 재처리(재전달) 두 번
    await expect(fanOutMessage(root, MATCH, "-b", message())).resolves.toBe("applied");
    await expect(fanOutMessage(root, MATCH, "-b", message())).resolves.toBe("applied");
    expect(root.data[`userChatRooms/me/${MATCH}`]).toMatchObject({ lastMessage: "안녕", unreadCount: 0 });
    expect(root.data[`userChatRooms/other/${MATCH}`]).toMatchObject({ lastMessage: "안녕", unreadCount: 1 });
  });

  it("배포 지역은 RTDB 인스턴스 지역(asia-southeast1), 에뮬레이터에서만 us-central1 이다", () => {
    expect(CHAT_TRIGGER_REGION).toBe("asia-southeast1");
    expect(chatTriggerRegion({})).toBe("asia-southeast1");
    expect(chatTriggerRegion({ FUNCTIONS_EMULATOR: "false" })).toBe("asia-southeast1");
    expect(chatTriggerRegion({ FUNCTIONS_EMULATOR: "true" })).toBe("us-central1");
  });
});

describe("BT-022 트리거 재처리 정책 (PR #96 재검토 P2)", () => {
  const NOW = Date.parse("2026-11-13T12:00:00Z");
  const silent = { warn: vi.fn(), error: vi.fn() };
  const seed = () => memoryRoot({
    [`chatRooms/${MATCH}/members`]: { me: { joinedAt: 1 }, other: { joinedAt: 1 } },
    [`userChatRooms/me/${MATCH}`]: entry(),
    [`userChatRooms/other/${MATCH}`]: entry({ otherUid: "me" }),
  });
  const event = (root: RootRef, ageMs: number, value: unknown = message()) => ({
    time: new Date(NOW - ageMs).toISOString(), matchId: MATCH, messageId: "-b", root, value,
  });

  it("배포 설정: 실패 재시도(retry)가 켜져 있고 지역은 asia-southeast1 이다", () => {
    const endpoint = (createMessageTrigger() as unknown as {
      __endpoint: { region: string[]; eventTrigger: { retry: boolean; eventFilterPathPatterns: { ref: string } } };
    }).__endpoint;
    expect(endpoint.eventTrigger.retry).toBe(true);
    expect(endpoint.region).toEqual(["asia-southeast1"]);
    expect(endpoint.eventTrigger.eventFilterPathPatterns.ref).toBe("chatRooms/{matchId}/messages/{messageId}");
  });

  it.each(["me", "other"])("일시 오류(%s 쪽 실패)는 예외를 다시 던져 재시도되고, 재시도에서 한 번만 반영된다", async (failed) => {
    const root = seed();
    root.failOnce.add(`userChatRooms/${failed}/${MATCH}`);
    // 1차 시도: 한쪽 트랜잭션 실패 → 예외가 그대로 올라가야 플랫폼이 재시도한다
    await expect(handleMessageEvent(event(root, 5_000), NOW, fanOutMessage, silent)).rejects.toThrow("transaction failed");
    // 플랫폼 재시도(같은 이벤트, 발생 시각 그대로) 두 번
    await expect(handleMessageEvent(event(root, 60_000), NOW, fanOutMessage, silent)).resolves.toBe("applied");
    await expect(handleMessageEvent(event(root, 120_000), NOW, fanOutMessage, silent)).resolves.toBe("applied");
    expect(root.data[`userChatRooms/me/${MATCH}`]).toMatchObject({ lastMessage: "안녕", unreadCount: 0 });
    expect(root.data[`userChatRooms/other/${MATCH}`]).toMatchObject({ lastMessage: "안녕", unreadCount: 1 });
  });

  it("발생 후 1시간이 지난 이벤트는 처리하지 않고 정상 종료해 재시도를 멈춘다", async () => {
    const root = seed();
    const fanOut = vi.fn(fanOutMessage);
    const logger = { warn: vi.fn(), error: vi.fn() };
    await expect(handleMessageEvent(event(root, MAX_EVENT_AGE_MS + 1), NOW, fanOut, logger)).resolves.toBe("expired");
    expect(fanOut).not.toHaveBeenCalled();
    expect(logger.error).toHaveBeenCalledOnce();
    expect(root.data[`userChatRooms/other/${MATCH}`]).toEqual(entry({ otherUid: "me" }));
    // 1시간 직전까지는 처리한다
    await expect(handleMessageEvent(event(root, MAX_EVENT_AGE_MS - 1), NOW, fanOut, logger)).resolves.toBe("applied");
  });

  it("발생 시각을 읽을 수 없는 이벤트도 재시도하지 않는다", async () => {
    const fanOut = vi.fn(fanOutMessage);
    await expect(handleMessageEvent({ ...event(seed(), 0), time: "not-a-date" }, NOW, fanOut, silent))
      .resolves.toBe("expired");
    expect(fanOut).not.toHaveBeenCalled();
  });

  it("영구 결과(형식 오류·비멤버 발신자)는 예외 없이 끝나 재시도하지 않는다", async () => {
    const root = seed();
    await expect(handleMessageEvent(event(root, 0, { senderId: "me" }), NOW, fanOutMessage, silent))
      .resolves.toBe("invalid-message");
    await expect(handleMessageEvent(event(root, 0, message("outsider")), NOW, fanOutMessage, silent))
      .resolves.toBe("not-member");
  });
});
