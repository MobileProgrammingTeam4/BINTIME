import { HttpsError } from "firebase-functions/v2/https";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ChatLeaveService } from "../src/chat/leave";
import { createChatLeaveCallable, leaveInput } from "../src/chat/leaveCallable";

/** BT-043 Callable 경계: 인증·학교 이메일·입력·오류 변환. 실제 나가기 동작은 emulator-tests/chat-leave.test.ts. */
const room = "a".repeat(64);
const school = { email: "me@school.ac.kr", email_verified: true };

describe("leaveChatRoom Callable", () => {
  const calls: Array<[string, string]> = [];
  let behavior: () => Promise<unknown> = async () => ({ chatRoomId: room, left: true });
  const fake = { leave: async (roomId: string, uid: string) => { calls.push([roomId, uid]); return behavior(); } } as unknown as ChatLeaveService;
  const fn = createChatLeaveCallable(() => fake);
  const call = (data: unknown, auth?: { uid: string; token: object }) => fn.run({ data, auth, rawRequest: {} } as never);

  beforeEach(() => {
    vi.stubEnv("ALLOWED_EMAIL_SUFFIXES", ".ac.kr,.edu");
    calls.length = 0;
    behavior = async () => ({ chatRoomId: room, left: true });
  });
  afterEach(() => vi.unstubAllEnvs());

  it("로그인 없으면 unauthenticated, 학교 이메일이 아니면 permission-denied 이고 서비스를 부르지 않는다", async () => {
    await expect(call({ chatRoomId: room })).rejects.toMatchObject({ code: "unauthenticated" });
    await expect(call({ chatRoomId: room }, { uid: "me", token: { ...school, email: "me@gmail.com" } })).rejects.toMatchObject({ code: "permission-denied" });
    await expect(call({ chatRoomId: room }, { uid: "me", token: { ...school, email_verified: false } })).rejects.toMatchObject({ code: "permission-denied" });
    expect(calls).toEqual([]);
  });

  it("입력은 64자리 hex chatRoomId 하나만 받는다(UID·상태 등 추가 필드 거부)", async () => {
    for (const data of [{}, { chatRoomId: "abc" }, { chatRoomId: room.toUpperCase() }, { chatRoomId: room, uid: "other" }, { chatRoomId: room, status: "ENDED" }, null]) {
      await expect(call(data, { uid: "me", token: school })).rejects.toMatchObject({ code: "invalid-argument" });
    }
    expect(leaveInput.safeParse({ chatRoomId: room }).success).toBe(true);
    expect(calls).toEqual([]);
  });

  it("토큰 UID 로 나가고 결과를 그대로 돌려준다", async () => {
    expect(await call({ chatRoomId: room }, { uid: "me", token: school })).toEqual({ chatRoomId: room, left: true });
    expect(calls).toEqual([[room, "me"]]);
  });

  it("서비스의 HttpsError 는 그대로, 예상하지 못한 오류는 RETRY_LATER 로 바꾼다", async () => {
    behavior = async () => { throw new HttpsError("failed-precondition", "x", { reason: "ACTIVE_MATCH" }); };
    await expect(call({ chatRoomId: room }, { uid: "me", token: school })).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "ACTIVE_MATCH" } });
    behavior = async () => { throw new Error("boom"); };
    await expect(call({ chatRoomId: room }, { uid: "me", token: school })).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "RETRY_LATER" } });
  });
});
