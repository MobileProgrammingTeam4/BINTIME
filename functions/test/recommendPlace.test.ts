import { logger } from "firebase-functions";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  CAMPUS_PLACES_VERSION, type CampusPlace, candidatePlaces, displayName, isOpenDuring, VERIFIED_CAMPUS_PLACES,
} from "../src/ai/campusPlaces";
import { runAiTask } from "../src/ai/gateway";
import { MemoryPlaceStore, type PlaceMatch } from "../src/ai/placeStore";
import { FALLBACK_REASON, recommendPlaceTask, safeReason } from "../src/ai/tasks/recommendPlace";
import type { CourseInfo } from "../src/matching/score";
import { ctx, expectReason, makeDeps } from "./helpers";

const me = "uid-me";
const other = "uid-other";
const byId = (id: string) => VERIFIED_CAMPUS_PLACES.find((p) => p.placeId === id)!;

describe("검증된 교내 장소 목록", () => {
  it("모든 장소에 국민대 공식 출처·확인 날짜·고유 id 가 있다", () => {
    expect(VERIFIED_CAMPUS_PLACES).toHaveLength(21);
    expect(new Set(VERIFIED_CAMPUS_PLACES.map((p) => p.placeId)).size).toBe(21);
    for (const p of VERIFIED_CAMPUS_PLACES) {
      expect(p.source.length).toBeGreaterThan(0);
      for (const url of p.source) expect(new URL(url).hostname.endsWith("kookmin.ac.kr")).toBe(true);
      expect(p.verifiedAt).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(displayName(p).length).toBeLessThanOrEqual(60);
      for (const r of p.hours?.ranges ?? []) expect(r.open).toBeLessThan(r.close);
    }
  });

  it("이름에 건물이 없으면 페이지의 건물·층을 붙여 보여 준다", () => {
    expect(displayName(byId("welfare-student-cafeteria"))).toBe("학생식당 (종합복지관 1층)");
    expect(displayName(byId("law-cafe"))).toBe("법학관 카페 (쿱스켓 내부)");
  });

  it("평일만 여는 카페는 토요일 약속에서 빠지고, 요일이 없는 식당은 요일로 거르지 않는다", () => {
    expect(isOpenDuring(byId("engineering-cafe"), 6, 720, 810)).toBe(false);
    expect(isOpenDuring(byId("engineering-cafe"), 1, 720, 810)).toBe(true);
    expect(isOpenDuring(byId("bugak-subway"), 6, 720, 810)).toBe(true);
  });

  it("약속 중 30분 이상 열려 있어야 한다(경계)", () => {
    const cafe = byId("engineering-cafe"); // 평일 08:30~17:30
    expect(isOpenDuring(cafe, 1, 1020, 1110)).toBe(true); // 17:00~18:30 → 30분
    expect(isOpenDuring(cafe, 1, 1021, 1110)).toBe(false); // 29분
    expect(isOpenDuring(cafe, 1, 1050, 1140)).toBe(false); // 17:30 이후
    // 학생식당은 15:00~17:00 쉬는 시간이 있다
    expect(isOpenDuring(byId("welfare-student-cafeteria"), 2, 900, 1020)).toBe(false);
  });

  it("교직원식당은 평일 중식·석식, 금요일은 중식만, 주말은 휴점", () => {
    const staff = byId("welfare-staff-cafeteria");
    expect(displayName(staff)).toBe("교직원식당 (종합복지관 1층)");
    expect(isOpenDuring(staff, 1, 720, 810)).toBe(true); // 월 12:00~13:30 중식
    expect(isOpenDuring(staff, 4, 1020, 1110)).toBe(true); // 목 17:00~18:30 석식
    expect(isOpenDuring(staff, 5, 720, 810)).toBe(true); // 금 중식
    expect(isOpenDuring(staff, 5, 1020, 1110)).toBe(false); // 금 석식 미운영
    expect(isOpenDuring(staff, 6, 720, 810)).toBe(false); // 토 휴점
    expect(isOpenDuring(staff, 1, 870, 1020)).toBe(false); // 14:30~17:00 쉬는 시간
    expect(candidatePlaces("밥", 5, 1020, 1110).map((p) => p.placeId)).not.toContain("welfare-staff-cafeteria");
  });

  it("운영 시간이 불확실한 장소(hours=null)는 거르지 않는다", () => {
    const unknown: CampusPlace = { ...byId("law-cafe"), placeId: "x", hours: null };
    expect(isOpenDuring(unknown, 7, 0, 60)).toBe(true);
  });

  it("목적이 스터디·밥·카페·디저트면 맞는 곳만, 전체·산책이면 목적으로 거르지 않는다", () => {
    const meal = candidatePlaces("밥", 1, 720, 810);
    expect(meal.length).toBeGreaterThan(0);
    expect(meal.every((p) => p.purposes.includes("밥"))).toBe(true);
    expect(candidatePlaces("스터디", 1, 720, 810).every((p) => p.category === "CAFE")).toBe(true);
    expect(candidatePlaces("산책", 1, 720, 810)).toHaveLength(candidatePlaces("전체", 1, 720, 810).length);
    expect(candidatePlaces(null, 1, 720, 810).length).toBe(21);
  });
});

describe("이유 문구 검사", () => {
  const cafe = byId("engineering-cafe");
  it("장소 자신의 건물이 아닌 수업 건물 이름이나 운영 시간이 있으면 기본 문구로 바꾼다", () => {
    expect(safeReason("법학관에서 오시기 편해요", cafe, ["공학관", "법학관"])).toBe(FALLBACK_REASON);
    expect(safeReason("17시까지 열어요", cafe, [])).toBe(FALLBACK_REASON);
    expect(safeReason("17:30 마감 전에 가요", cafe, [])).toBe(FALLBACK_REASON);
    expect(safeReason("공학관 1층이라 찾기 쉬워요", cafe, ["공학관"])).toBe("공학관 1층이라 찾기 쉬워요");
  });
});

describe("recommendPlace live", () => {
  afterEach(() => vi.restoreAllMocks());

  // 월요일 12:00~13:30, 목적 카페·디저트. 나는 직전 수업이 공학관, 상대는 직후 수업이 법학관(서로 다른 건물).
  const match: PlaceMatch = { participantUids: [me, other], purpose: "카페·디저트", dayOfWeek: 1, startMinute: 720, endMinute: 810 };
  const courses: Record<string, CourseInfo[]> = {
    [me]: [{ name: "자료구조", classroom: "공학관 302호", slots: [{ dayOfWeek: 1, startMinute: 600, endMinute: 690 }] }],
    [other]: [{ name: "민법", classroom: "법학관 501호", slots: [{ dayOfWeek: 1, startMinute: 840, endMinute: 930 }] }],
  };
  const answer = (places: { placeId: string; name?: string; reason: string }[]) =>
    ({ text: JSON.stringify({ places: places.map((p) => ({ name: p.name ?? "모델이 쓴 이름", ...p })) }) });

  function live(steps: { text: string }[], store = new MemoryPlaceStore({ "m-1": match }, courses)) {
    const deps = makeDeps({ mode: "live", matches: { "m-1": [me, other] }, steps });
    return Object.assign(deps, { places: store, store });
  }

  it("서로 다른 수업 건물을 건물명만 보내고, 이름·UID·호실·운영 시간은 보내지 않는다", async () => {
    const deps = live([answer([{ placeId: "engineering-cafe", reason: "가볍게 커피 마시기 좋아요" }])]);
    await runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), deps);
    const call = deps.client.calls[0];
    const payload = JSON.parse(call.contents as string);
    expect(payload).toMatchObject({ purpose: "카페·디저트", day: "월", time: "12:00~13:30",
      personA: { nearbyClassBuildings: ["공학관"] }, personB: { nearbyClassBuildings: ["법학관"] } });
    const raw = call.contents as string;
    for (const leak of [me, other, "302호", "501호", "자료구조", "민법", "hours", "verifiedAt", "kookmin.ac.kr"]) expect(raw).not.toContain(leak);
    // 후보는 목적·운영 시간을 통과한 목록 장소뿐
    const ids = payload.places.map((p: { placeId: string }) => p.placeId);
    expect(ids).toEqual(candidatePlaces("카페·디저트", 1, 720, 810).map((p) => p.placeId));
    expect(ids).not.toContain("law-hanul");
  });

  it("후보 밖(목적이 다른 곳)·중복 id 는 버리고 이름은 서버 목록 값으로 바꾼 뒤 저장한다", async () => {
    // 목록 밖 id 는 아래 "두 번 모두 후보 밖" 에서 본다(응답은 최대 3곳).
    const deps = live([answer([
      { placeId: "law-hanul", reason: "밥 먹기 좋아요" },
      { placeId: "engineering-cafe", name: "공대 카페", reason: "가볍게 커피 마시기 좋아요" },
      { placeId: "engineering-cafe", reason: "중복" },
    ])]);
    const res = await runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), deps);
    expect(res.data.places).toEqual([{ placeId: "engineering-cafe", name: "공학관 카페", reason: "가볍게 커피 마시기 좋아요" }]);
    expect(deps.store.saved.get("m-1")).toEqual({ version: CAMPUS_PLACES_VERSION,
      places: [{ placeId: "engineering-cafe", reason: "가볍게 커피 마시기 좋아요" }] });
  });

  it("저장된 추천이 있으면 Gemini 를 부르지 않고 호출 횟수도 쓰지 않는다(상대도 같은 추천)", async () => {
    const store = new MemoryPlaceStore({ "m-1": match }, courses);
    store.saved.set("m-1", { version: CAMPUS_PLACES_VERSION, places: [{ placeId: "bugak-cafe", reason: "넓어요" }] });
    const info = vi.spyOn(logger, "info").mockImplementation(() => undefined);
    const deps = live([], store);
    const res = await runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(other), deps);
    expect(res.data.places).toEqual([{ placeId: "bugak-cafe", name: "북악관 카페", reason: "넓어요" }]);
    expect(deps.client.calls).toHaveLength(0);
    expect(deps.usageStore.counts.size).toBe(0);
    expect(info.mock.calls.find(([m]) => m === "ai_call")?.[1]).toMatchObject({ outcome: "cache" });
  });

  it("목록 버전이 바뀌었거나 저장된 장소가 모두 후보 밖이면 다시 추천한다", async () => {
    const store = new MemoryPlaceStore({ "m-1": match }, courses);
    store.saved.set("m-1", { version: CAMPUS_PLACES_VERSION - 1, places: [{ placeId: "bugak-cafe", reason: "넓어요" }] });
    const deps = live([answer([{ placeId: "science-cafe", reason: "조용해요" }])], store);
    expect((await runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), deps)).data.places[0].placeId).toBe("science-cafe");

    store.saved.set("m-1", { version: CAMPUS_PLACES_VERSION, places: [{ placeId: "law-hanul", reason: "밥" }] });
    const again = live([answer([{ placeId: "bugak-cafe", reason: "넓어요" }])], store);
    expect((await runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), again)).data.places[0].placeId).toBe("bugak-cafe");
    expect(again.client.calls).toHaveLength(1);
  });

  it("이유 문구에 상대 수업 건물이 나오면 기본 문구로 바꾼다", async () => {
    const deps = live([answer([{ placeId: "engineering-cafe", reason: "법학관에서 수업 끝나고 오기 좋아요" }])]);
    const res = await runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), deps);
    expect(res.data.places[0].reason).toBe(FALLBACK_REASON);
  });

  it("약속 시간에 여는 장소가 없으면 Gemini 를 부르지 않고 횟수도 쓰지 않는다", async () => {
    const late: PlaceMatch = { ...match, purpose: "밥", dayOfWeek: 7, startMinute: 1260, endMinute: 1380 }; // 일 21:00~23:00
    const deps = live([], new MemoryPlaceStore({ "m-1": late }, courses));
    const error = await expectReason(runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), deps), "AI_UNAVAILABLE");
    expect(error.retryable).toBe(false);
    expect(deps.client.calls).toHaveLength(0);
    expect(deps.usageStore.counts.size).toBe(0);
  });

  it("참여자가 아니면 PERMISSION_DENIED", async () => {
    const deps = live([]);
    await expectReason(runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx("uid-stranger"), deps), "PERMISSION_DENIED");
  });

  it("두 번 모두 후보 밖 장소면 AI_INVALID_OUTPUT 이고 저장하지 않는다", async () => {
    const bad = answer([{ placeId: "invented-cafe", reason: "새로 생겼어요" }]);
    const deps = live([bad, bad]);
    await expectReason(runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), deps), "AI_INVALID_OUTPUT");
    expect(deps.store.saved.size).toBe(0);
  });

  it("저장에 실패해도 추천은 돌려준다", async () => {
    const store = new MemoryPlaceStore({ "m-1": match }, courses);
    store.save = async () => { throw new Error("firestore down"); };
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);
    const deps = live([answer([{ placeId: "engineering-cafe", reason: "가볍게 커피 마시기 좋아요" }])], store);
    expect((await runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), deps)).data.places).toHaveLength(1);
    expect(warn).toHaveBeenCalledWith("ai_cache_save_failed", expect.objectContaining({ task: "recommendPlace" }));
  });
});

describe("recommendPlace mock", () => {
  it("Mock 은 장소 저장소 없이 Figma 시안 장소를 돌려준다", async () => {
    const deps = makeDeps({ matches: { "m-1": [me, other] } });
    const res = await runAiTask(recommendPlaceTask, { matchId: "m-1" }, ctx(me), deps);
    expect(res.data.places.map((p) => p.name)).toEqual(["복지관 지하1층 카페", "북악도서관"]);
  });
});
