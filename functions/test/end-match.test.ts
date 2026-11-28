import { describe, expect, it } from "vitest";
import { endDecision } from "../src/requests/endMatch";

const snapshot = { meetingDate: "2026-11-24", startMinute: 600, endMinute: 660 };
const at = (time: string) => new Date(`2026-11-24T${time}+09:00`);
describe("BT-038 server end boundary", () => {
  it("scheduled ending waits until the exact stored end", () => {
    expect(endDecision(snapshot, at("10:59:59.999"), false)).toBeNull();
    expect(endDecision(snapshot, at("11:00:00"), false)).toMatchObject({ endReason: "AUTO", reviewEligible: true });
  });
  it("early manual ending is permanently ineligible; start boundary is eligible", () => {
    expect(endDecision(snapshot, at("09:59:59.999"), true)).toMatchObject({ endReason: "MANUAL", reviewEligible: false });
    expect(endDecision(snapshot, at("10:00:00"), true)).toMatchObject({ endReason: "MANUAL", reviewEligible: true });
  });
  it("late manual calls use the scheduled end, not cleanup execution time", () => {
    const decision = endDecision(snapshot, at("12:00:00"), true)!;
    expect(decision.endedAt.toMillis()).toBe(at("11:00:00").getTime());
    expect(decision.endReason).toBe("AUTO");
  });
  it("24:00 is the following KST midnight, independent of device timezone", () => {
    const time = { ...snapshot, endMinute: 1440 };
    expect(endDecision(time, new Date("2026-11-24T14:59:59Z"), false)).toBeNull();
    expect(endDecision(time, new Date("2026-11-24T15:00:00Z"), false)?.reviewEligible).toBe(true);
  });
  it.each([{ ...snapshot, startMinute: -1 }, { ...snapshot, startMinute: 660 },
    { ...snapshot, meetingDate: "2026-02-30" }, { ...snapshot, endMinute: 1441 }])("rejects invalid snapshots %#", bad => {
    expect(() => endDecision(bad, at("12:00:00"), true)).toThrow();
  });
});
