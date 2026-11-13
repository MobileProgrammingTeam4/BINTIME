import { describe, expect, it } from "vitest";
import { verifiedSlots } from "../src/requests/candidates";
import { createRequestCallable } from "../src/requests/callable";
import type { RequestService } from "../src/requests/service";

const mask = (start: number, end: number) => 2 ** (end / 30) - 2 ** (start / 30);
const parent = { uid: "me", revision: 2, freeCells: [mask(600, 690), 0, 0, 0, 0, 0, 0] };
const slot = { dayOfWeek: 1, startMinute: 600, endMinute: 690 };
const avail = { sourceRevision: 2, slots: [slot] };

describe("BT-019 요청 직전 공강 원본 검증", () => {
  it("선택 원본과 같은 공강만 허용하고 형식 순서는 의미에 영향을 주지 않는다", () => {
    expect(verifiedSlots("me", parent, avail, [])).toEqual([slot]);
  });
  it.each([1, 3, null, "2"])("오래되거나 잘못된 sourceRevision %s를 거부한다", revision => {
    expect(() => verifiedSlots("me", parent, { ...avail, sourceRevision: revision }, [])).toThrow();
  });
  it("30분 경계가 아닌 값·선택하지 않은 시간·수업과 겹침을 거부한다", () => {
    expect(() => verifiedSlots("me", parent, { ...avail, slots: [{ ...slot, startMinute: 601 }] }, [])).toThrow();
    expect(() => verifiedSlots("me", parent, { ...avail, slots: [{ ...slot, startMinute: 630 }] }, [])).toThrow();
    expect(() => verifiedSlots("me", parent, avail, [{ timeSlots: [{ ...slot, startMinute: 689, endMinute: 700 }] }])).toThrow();
    expect(verifiedSlots("me", parent, avail, [{ timeSlots: [{ ...slot, startMinute: 690, endMinute: 720 }] }])).toEqual([slot]);
  });
  it("원본에서는 30분 선택을 보존하지만 매칭용에는 60분 미만을 포함하지 않는다", () => {
    const p = { ...parent, freeCells: [mask(600, 630), 0, 0, 0, 0, 0, 0] };
    expect(verifiedSlots("me", p, { ...avail, slots: [] }, [])).toEqual([]);
    expect(() => verifiedSlots("me", p, { ...avail, slots: [{ ...slot, endMinute: 630 }] }, [])).toThrow();
  });
  it("분할·중복 슬롯이나 잘못된 수업 문서는 안전하게 실패한다", () => {
    expect(() => verifiedSlots("me", parent, { ...avail, slots: [slot, slot] }, [])).toThrow();
    expect(() => verifiedSlots("me", parent, avail, [{}])).toThrow();
    expect(() => verifiedSlots("me", { ...parent, uid: "other" }, avail, [])).toThrow();
    expect(() => verifiedSlots("me", { ...parent, freeCells: [2 ** 48, 0, 0, 0, 0, 0, 0] }, avail, [])).toThrow();
  });
  it("주말·자정·마지막 30분 비트를 32비트 손실 없이 처리한다", () => {
    const p = { ...parent, freeCells: [0, 0, 0, 0, 0, 0, mask(1380, 1440)] };
    const s = { dayOfWeek: 7, startMinute: 1380, endMinute: 1440 };
    expect(verifiedSlots("me", p, { ...avail, slots: [s] }, [])).toEqual([s]);
  });
});

describe("BT-019 학교 이메일 자격", () => {
  const school = { email: "test@school.ac.kr", email_verified: true };
  it.each([{ ...school, email_verified: false }, { ...school, email: "test@gmail.com" }, {}])(
    "미인증/비학교/이메일 누락은 저장소를 호출하지 않는다", async token => {
      const callable = createRequestCallable(() => { throw Error("must not read database"); }, () => [".ac.kr"]);
      await expect(callable.run({ auth: { uid: "me", token }, data: {} } as never)).rejects.toMatchObject({
        code: "permission-denied", details: { reason: "PERMISSION_DENIED" },
      });
    });
  it("허용 목록 미설정도 거부한다", async () => {
    const callable = createRequestCallable(() => { throw Error("must not read database"); }, () => []);
    await expect(callable.run({ auth: { uid: "me", token: school }, data: {} } as never)).rejects.toMatchObject({ code: "permission-denied" });
  });
  it("검증된 학교 계정만 토큰 UID를 서비스에 넘긴다", async () => {
    const callable = createRequestCallable(() => ({ create: async (uid: string) => ({ fromUid: uid }) }) as RequestService, () => [".ac.kr"]);
    const data = { candidateId: "peer", clientRequestId: "c441f472-1d6c-4ba3-87ee-f0554b87da22" };
    expect(await callable.run({ auth: { uid: "me", token: school }, data } as never)).toEqual({ fromUid: "me" });
  });
});
