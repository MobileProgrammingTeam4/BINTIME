import { describe, expect, it } from "vitest";
import {
  bestOverlap,
  commonPurpose,
  parseSettings,
  parseSlots,
  referenceWindow,
  seoulTime,
  type MatchSettings,
  type Slot,
} from "../src/matching/eligibility";
import { createSearchCallable } from "../src/matching/callable";
import { type MatchStore, resolveCandidate, searchMatches } from "../src/matching/search";
import { withPlacePreview } from "../src/matching/places";
import { candidatePlaces, displayName, VERIFIED_CAMPUS_PLACES } from "../src/ai/campusPlaces";

// 2026-11-16(월) 14:10 KST
const MON_1410 = new Date("2026-11-16T05:10:00Z");
const on = (minOverlapMinutes = 60, purposes: string[] = []): MatchSettings => ({ isMatching: true, minOverlapMinutes, purposes });
const s = (dayOfWeek: number, startMinute: number, endMinute: number): Slot => ({ dayOfWeek, startMinute, endMinute });

class MemoryStore implements MatchStore {
  constructor(
    private readonly data: Record<string, { settings: MatchSettings | null; slots: Slot[] }>,
    private readonly blocks: [string, string][] = [],
  ) {}
  async settings(uid: string) { return this.data[uid]?.settings ?? null; }
  async slots(uid: string) { return this.data[uid]?.slots ?? []; }
  async blocked(a: string, b: string) { return this.blocks.some(([x, y]) => x === a && y === b); }
  async matchingUids() { return Object.keys(this.data).filter((uid) => this.data[uid].settings?.isMatching); }
}

describe("seoulTime", () => {
  it("UTC 15:00 은 서울 다음날 00:00", () => {
    expect(seoulTime(new Date("2026-11-15T15:00:00Z"))).toEqual({ date: "2026-11-16", dayOfWeek: 1, minute: 0 });
  });
  it("일요일은 7", () => {
    expect(seoulTime(MON_1410, -1).dayOfWeek).toBe(7);
  });
});

describe("referenceWindow", () => {
  it("지금 진행 중인 공강은 시작을 지금으로 자른다", () => {
    expect(referenceWindow([s(1, 840, 960)], 60, MON_1410)).toEqual({ meetingDate: "2026-11-16", dayOfWeek: 1, startMinute: 850, endMinute: 960 });
  });
  it("남은 시간이 최소 겹침보다 짧으면 다음 공강", () => {
    expect(referenceWindow([s(1, 840, 900), s(1, 1020, 1080)], 60, MON_1410)?.startMinute).toBe(1020);
  });
  it("오늘 없으면 가장 가까운 미래 요일", () => {
    expect(referenceWindow([s(3, 600, 720), s(2, 780, 840)], 60, MON_1410)).toEqual({ meetingDate: "2026-11-17", dayOfWeek: 2, startMinute: 780, endMinute: 840 });
  });
  it("오늘 이미 지난 공강만 있으면 다음 주 같은 요일", () => {
    expect(referenceWindow([s(1, 540, 660)], 60, MON_1410)?.meetingDate).toBe("2026-11-23");
  });
  it("공강이 하나도 없으면 null", () => {
    expect(referenceWindow([], 60, MON_1410)).toBeNull();
  });
});

describe("bestOverlap 경계값", () => {
  const ref = { meetingDate: "2026-11-16", dayOfWeek: 1, startMinute: 840, endMinute: 960 };
  it("정확히 60분은 포함, 59분은 제외", () => {
    expect(bestOverlap(ref, [s(1, 900, 1000)], 60)?.endMinute).toBe(960);
    expect(bestOverlap(ref, [s(1, 901, 1000)], 60)).toBeNull();
  });
  it("끝과 시작이 맞닿으면 겹침 0", () => {
    expect(bestOverlap(ref, [s(1, 960, 1080)], 60)).toBeNull();
  });
  it("다른 요일은 비교하지 않는다", () => {
    expect(bestOverlap(ref, [s(2, 840, 960)], 60)).toBeNull();
  });
  it("가장 긴 교집합을 고른다", () => {
    expect(bestOverlap(ref, [s(1, 780, 900), s(1, 870, 1000)], 60)?.startMinute).toBe(870);
  });
});

describe("commonPurpose", () => {
  it("빈 목록은 전체", () => {
    expect(commonPurpose([], [])).toBe("전체");
    expect(commonPurpose([], ["밥"])).toBe("밥");
    expect(commonPurpose(["스터디"], [])).toBe("스터디");
  });
  it("겹치는 목적이 없으면 제외", () => {
    expect(commonPurpose(["스터디"], ["밥"])).toBeNull();
    expect(commonPurpose(["스터디", "밥"], ["밥"])).toBe("밥");
  });
});

describe("문서 파싱", () => {
  it("허용되지 않은 최소 겹침은 기본 60", () => {
    expect(parseSettings({ isMatching: true, minOverlapMinutes: 30 })?.minOverlapMinutes).toBe(60);
  });
  it("문서가 없으면 null(=OFF)", () => {
    expect(parseSettings(undefined)).toBeNull();
  });
  const rev = { revision: 3 };
  const avail = (slots: unknown[], sourceRevision = 3) => ({ slots, sourceRevision });
  it("잘못된 슬롯은 버린다", () => {
    expect(parseSlots(avail([s(1, 600, 660), { dayOfWeek: 8, startMinute: 0, endMinute: 60 }, s(1, 700, 650)]), rev)).toEqual([s(1, 600, 660)]);
  });
  it("sourceRevision 이 시간표 revision 과 다르거나 시간표가 없으면 빈 목록", () => {
    expect(parseSlots(avail([s(1, 600, 660)], 2), rev)).toEqual([]);
    expect(parseSlots(avail([s(1, 600, 660)]), undefined)).toEqual([]);
    expect(parseSlots({ slots: [s(1, 600, 660)] }, rev)).toEqual([]);
  });
  it("30분 경계가 아닌 슬롯은 버린다 (10:01~11:31)", () => {
    expect(parseSlots(avail([s(1, 601, 691)]), rev)).toEqual([]);
  });
  it("붙거나 겹친 슬롯은 합치고, 합친 뒤 60분 미만은 버린다", () => {
    expect(parseSlots(avail([s(2, 630, 660), s(1, 600, 630), s(1, 630, 660), s(1, 720, 750)]), rev)).toEqual([s(1, 600, 660)]);
    expect(parseSlots(avail([s(1, 600, 690), s(1, 660, 720)]), rev)).toEqual([s(1, 600, 720)]);
  });
});

describe("searchMatches", () => {
  const meSlots = [s(1, 840, 960)];
  it("내 스위치 OFF", async () => {
    const store = new MemoryStore({ me: { settings: { ...on(), isMatching: false }, slots: meSlots } });
    expect((await searchMatches(store, "me", MON_1410)).status).toBe("MATCHING_OFF");
  });
  it("매칭할 공강 없음", async () => {
    const store = new MemoryStore({ me: { settings: on(), slots: [] } });
    expect((await searchMatches(store, "me", MON_1410)).status).toBe("NO_FREE_SLOT");
  });
  it("상대 OFF·목적 불일치·겹침 부족·양방향 차단은 제외하고 겹침 긴 순", async () => {
    const store = new MemoryStore(
      {
        me: { settings: on(60, ["스터디"]), slots: meSlots },
        long: { settings: on(60, ["스터디"]), slots: [s(1, 800, 1000)] },
        short: { settings: on(60, []), slots: [s(1, 880, 960)] },
        off: { settings: { ...on(), isMatching: false }, slots: [s(1, 800, 1000)] },
        food: { settings: on(60, ["밥"]), slots: [s(1, 800, 1000)] },
        tiny: { settings: on(), slots: [s(1, 900, 950)] },
        blockedByMe: { settings: on(), slots: [s(1, 800, 1000)] },
        blocksMe: { settings: on(), slots: [s(1, 800, 1000)] },
      },
      [["me", "blockedByMe"], ["blocksMe", "me"]],
    );
    const result = await searchMatches(store, "me", MON_1410);
    expect(result.status).toBe("OK");
    expect(result.candidates.map((c) => c.candidateId)).toEqual(["long", "short"]);
    expect(result.candidates[0]).toEqual({
      candidateId: "long", purpose: "스터디", meetingDate: "2026-11-16", startMinute: 850, endMinute: 960, overlapMinutes: 110,
    });
  });
});

describe("searchMatches Callable", () => {
  const store = new MemoryStore({ me: { settings: on(), slots: [s(1, 840, 960)] } });
  const fn = createSearchCallable(() => store, () => MON_1410, () => [".ac.kr"]);
  const call = (auth?: { uid: string; token: object }) => fn.run({ data: {}, auth, rawRequest: {} } as never);
  const school = { email: "me@school.ac.kr", email_verified: true };
  it("로그인 없으면 unauthenticated", async () => {
    await expect(call()).rejects.toMatchObject({ code: "unauthenticated" });
  });
  it("학교 이메일이 아니거나 미인증이면 permission-denied", async () => {
    await expect(call({ uid: "me", token: { ...school, email: "me@gmail.com" } })).rejects.toMatchObject({ code: "permission-denied" });
    await expect(call({ uid: "me", token: { ...school, email_verified: false } })).rejects.toMatchObject({ code: "permission-denied" });
  });
  it("학교 계정은 검색 결과", async () => {
    expect(await call({ uid: "me", token: school })).toMatchObject({ status: "OK", candidates: [] });
  });
  it("OK 결과 후보에 점수기를 적용한다(BT-027)", async () => {
    const two = new MemoryStore({ me: { settings: on(), slots: [s(1, 840, 960)] }, you: { settings: on(), slots: [s(1, 840, 960)] } });
    const scored = createSearchCallable(() => two, () => MON_1410, () => [".ac.kr"],
      async (_uid, candidates) => candidates.map((c) => ({ ...c, score: 70 })));
    const res = await scored.run({ data: {}, auth: { uid: "me", token: school }, rawRequest: {} } as never);
    expect(res).toMatchObject({ status: "OK", candidates: [{ candidateId: "you", score: 70 }] });
  });
  it("OK 결과 후보에 기준 공강 요일·겹친 시간으로 추천 장소를 붙인다(BT-042)", async () => {
    const two = new MemoryStore({ me: { settings: on(), slots: [s(1, 840, 960)] }, you: { settings: on(), slots: [s(1, 840, 960)] } });
    const fn2 = createSearchCallable(() => two, () => MON_1410, () => [".ac.kr"], async (_uid, candidates) => candidates);
    const res = await fn2.run({ data: {}, auth: { uid: "me", token: school }, rawRequest: {} } as never);
    const open = candidatePlaces("전체", 1, 850, 960);
    expect(open.length).toBeGreaterThan(1);
    expect(res).toMatchObject({ status: "OK", candidates: [{ candidateId: "you", placeName: displayName(open[0]), placeCount: open.length }] });
  });
});

describe("withPlacePreview (BT-042 검색 추천 장소)", () => {
  const c = { candidateId: "you", purpose: "밥", meetingDate: "2026-11-20", startMinute: 720, endMinute: 780, overlapMinutes: 60 };
  it("목적에 맞고 그 시간에 여는 검증 목록 장소만 센다", () => {
    const out = withPlacePreview(c, 5);
    const names = VERIFIED_CAMPUS_PLACES.filter((p) => p.purposes.includes("밥")).map(displayName);
    expect(names).toContain(out.placeName);
    expect(out.placeCount).toBe(candidatePlaces("밥", 5, 720, 780).length);
  });
  it("금요일 저녁은 석식을 안 하는 교직원식당을 빼고 센다", () => {
    const evening = { ...c, startMinute: 1020, endMinute: 1110 };
    expect(withPlacePreview(evening, 5).placeCount).toBe(withPlacePreview(evening, 4).placeCount! - 1);
  });
  it("여는 곳이 없으면 필드를 붙이지 않는다", () => {
    const late = withPlacePreview({ ...c, startMinute: 1320, endMinute: 1400 }, 1);
    expect(late).toEqual({ ...c, startMinute: 1320, endMinute: 1400 });
  });
});

describe("resolveCandidate (요청 생성 재검증)", () => {
  const store = new MemoryStore(
    { me: { settings: on(), slots: [s(1, 840, 960)] }, you: { settings: on(), slots: [s(1, 840, 960)] } },
    [],
  );
  it("검색과 같은 결과", async () => {
    expect((await resolveCandidate(store, "me", "you", MON_1410))?.overlapMinutes).toBe(110);
  });
  it("자기 자신·없는 사용자는 null", async () => {
    expect(await resolveCandidate(store, "me", "me", MON_1410)).toBeNull();
    expect(await resolveCandidate(store, "me", "ghost", MON_1410)).toBeNull();
  });
});
