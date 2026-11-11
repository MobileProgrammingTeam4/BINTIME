import { randomUUID } from "node:crypto";
import { describe, expect, it } from "vitest";
import { createRequestCallable } from "../src/requests/callable";
import { createInput, disconnectedCandidates, validateSnapshot } from "../src/requests/contracts";

const now = new Date("2026-11-11T00:00:00Z");
const valid = { toUid: "other", purpose: "스터디", meetingDate: "2026-11-11", startMinute: 600, endMinute: 660 };

describe("BT-019 요청 계약", () => {
  it("인증/후보/멱등 키만 허용하고 UID·시간 위조 필드는 거절한다", () => {
    const input = { candidateId: "candidate-1", clientRequestId: randomUUID() };
    expect(createInput.safeParse(input).success).toBe(true);
    expect(createInput.safeParse({ ...input, fromUid: "victim" }).success).toBe(false);
    expect(createInput.safeParse({ ...input, startMinute: 0 }).success).toBe(false);
    expect(createInput.safeParse({ ...input, clientRequestId: "invalid" }).success).toBe(false);
  });
  it.each([0, 30, 59, 60, 89, 90, 119, 120, 149, 150, 179, 180])("%i분 스냅샷의 최소 60분 경계를 검사한다", (duration) => {
    const run = () => validateSnapshot("me", { ...valid, endMinute: 600 + duration }, now);
    if (duration < 60) expect(run).toThrow(); else expect(run().endMinute).toBe(600 + duration);
  });
  it("자기 자신·잘못된 날짜·소수·범위 밖 시간·KST 종료 경계를 거절한다", () => {
    for (const patch of [{ toUid: "me" }, { meetingDate: "2026-02-30" }, { startMinute: 600.5 },
      { startMinute: -1 }, { endMinute: 1441 }, { endMinute: 540 }, { meetingDate: "2026-11-10" }]) {
      expect(() => validateSnapshot("me", { ...valid, ...patch }, now)).toThrow();
    }
    expect(() => validateSnapshot("me", valid, new Date("2026-11-11T02:00:00Z"))).toThrow();
  });
  it("BT-017 미연결은 성공·Mock 대체 없이 명시적으로 실패한다", async () => {
    await expect(disconnectedCandidates.resolve({} as never, "me", "candidate", now)).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "SEARCH_NOT_CONNECTED" },
    });
  });
  it("미로그인은 저장소에 접근하지 않고 거절한다", async () => {
    const callable = createRequestCallable(() => { throw new Error("must not access database"); });
    await expect(callable.run({ data: {} } as never)).rejects.toMatchObject({
      code: "unauthenticated", details: { reason: "UNAUTHENTICATED", requestId: expect.any(String) },
    });
  });
});
