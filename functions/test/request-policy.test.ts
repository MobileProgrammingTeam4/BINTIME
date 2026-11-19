import { describe, expect, it } from "vitest";
import { respondInput } from "../src/requests/contracts";
import { endsAt, overlaps } from "../src/requests/policy";

describe("BT-031 시간·수락 입력", () => {
  it("수락 입력에 UID·시간·임의 상태를 허용하지 않는다", () => {
    const input = { requestId: "a".repeat(64), action: "ACCEPTED" };
    expect(respondInput.safeParse(input).success).toBe(true);
    expect(respondInput.safeParse({ ...input, action: "REJECTED" }).success).toBe(true);
    for (const patch of [{ uid: "other" }, { startMinute: 0 }, { action: "CANCELED" }, { requestId: "../other" }]) {
      expect(respondInput.safeParse({ ...input, ...patch }).success).toBe(false);
    }
  });
  it("자정과 1440분은 KST 날짜 경계로 해석한다", () => {
    expect(endsAt({ meetingDate: "2026-11-17", endMinute: 1440 })).toBe(Date.parse("2026-11-17T15:00:00Z"));
    for (const value of [{ meetingDate: "2026-02-30", endMinute: 60 }, { meetingDate: "2026-11-17", endMinute: 1441 }]) {
      expect(() => endsAt(value)).toThrow();
    }
  });
  it("다른 날짜와 맞닿는 시간은 비충돌이다", () => {
    const a = { meetingDate: "2026-11-17", startMinute: 600, endMinute: 690 };
    expect(overlaps(a, { ...a, startMinute: 690, endMinute: 780 })).toBe(false);
    expect(overlaps(a, { ...a, meetingDate: "2026-11-18" })).toBe(false);
    expect(overlaps(a, { ...a, startMinute: 689 })).toBe(true);
  });
});
