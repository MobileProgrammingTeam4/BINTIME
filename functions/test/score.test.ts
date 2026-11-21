import { describe, expect, it } from "vitest";
import type { Candidate } from "../src/matching/eligibility";
import {
  AI_SCORE_LIMIT,
  addScores,
  commonCourses,
  type CourseInfo,
  type Evaluator,
  aiOverlap,
  buildingOf,
  nearbyBuildings,
  type CachedScore,
  inputHash,
  type PublicSide,
  rankByScore,
  SCORE_CACHE_TTL_MS,
  SCORE_DEADLINE_MS,
  type ScoreData,
  toSide,
} from "../src/matching/score";

const side: PublicSide = { department: "소프트웨어학과", grade: 3, tags: ["카페"] };
const course = (name: string, classroom: string, startMinute: number, endMinute: number, dayOfWeek = 1): CourseInfo => ({
  name, classroom, slots: [{ dayOfWeek, startMinute, endMinute }],
});
const cand = (id: string, overlapMinutes = 90): Candidate => ({
  candidateId: id, purpose: "전체", meetingDate: "2026-11-16", startMinute: 840, endMinute: 840 + overlapMinutes, overlapMinutes,
});

class MemoryData implements ScoreData {
  constructor(private readonly profiles: Record<string, PublicSide | null>, private readonly courseMap: Record<string, CourseInfo[]> = {}) {}
  async profile(uid: string) { return this.profiles[uid] ?? null; }
  async courses(uid: string) { return this.courseMap[uid] ?? []; }
  cache = new Map<string, CachedScore>();
  failSave = false;
  async cached(uid: string, id: string) { return this.cache.get(`${uid}_${id}`) ?? null; }
  async saveCache(uid: string, id: string, e: CachedScore) {
    if (this.failSave) throw new Error("write failed");
    this.cache.set(`${uid}_${id}`, e);
  }
}

const fixed: Evaluator = async () => ({
  task: "evaluateMatch", data: { score: 77, reasons: ["태그가 같아요"], suggestedActivity: "카페" },
  meta: { model: "m", requestId: "r", latencyMs: 1, mock: true },
});

describe("toSide", () => {
  it("'3학년' 문자열 학년을 숫자로, 태그 # 제거·중복 제거, 빈 bio 생략", () => {
    expect(toSide({ department: "경영학과", grade: "3학년", interestTags: ["#카페", "카페", "운동"], bio: " " }))
      .toEqual({ department: "경영학과", grade: 3, tags: ["카페", "운동"] });
  });
  it("bio 의 이메일·전화번호·링크를 지우고, 연락처뿐이면 bio 생략", () => {
    expect(toSide({ department: "경영학과", grade: "3", bio: "카페 좋아해요 010-1234-5678 a.b@kookmin.ac.kr https://x.io/y" })?.bio)
      .toBe("카페 좋아해요");
    expect(toSide({ department: "경영학과", grade: "3", bio: "01012345678" })).toEqual({ department: "경영학과", grade: 3, tags: [] });
  });
  it("학과나 학년이 없으면 null", () => {
    expect(toSide({ department: "경영학과", grade: "" })).toBeNull();
    expect(toSide(undefined)).toBeNull();
  });
});

describe("수업 기반 입력", () => {
  const courses = [course("자료구조", "공학관 201", 600, 690), course("운영체제", "본관 302", 960, 1050), course("영어", "인문관", 960, 1050, 2)];
  it("공강 직전·직후 같은 요일 수업 건물(호실 제외)", () => {
    expect(nearbyBuildings(courses, 1, 840, 930)).toEqual(["공학관", "본관"]);
  });
  it("AI 입력 겹침은 30분 단위로 내려 매분 바뀌지 않는다", () => {
    expect(aiOverlap(196)).toBe(180);
    expect(aiOverlap(195)).toBe(180);
    expect(aiOverlap(90)).toBe(90);
    expect(aiOverlap(60)).toBe(60);
  });
  it("건물명은 첫 숫자 앞까지, 숫자만 있으면 빈 값", () => {
    expect(buildingOf("미래관 611호")).toBe("미래관");
    expect(buildingOf("미래관611")).toBe("미래관");
    expect(buildingOf("온라인")).toBe("온라인");
    expect(buildingOf("611")).toBe("");
    expect(buildingOf(undefined)).toBe("");
  });
  it("함께 듣는 수업 이름", () => {
    expect(commonCourses(courses, [course("운영체제", "", 0, 60), course("미적분", "", 0, 60)])).toEqual(["운영체제"]);
  });
});

describe("addScores", () => {
  it(`상위 ${AI_SCORE_LIMIT}명에게만 점수를 붙이고 순서는 그대로`, async () => {
    const ids = ["a", "b", "c", "d", "e", "f"];
    const data = new MemoryData(Object.fromEntries([["me", side], ...ids.map((id) => [id, side])]));
    const out = await addScores(data, fixed, "me", ids.map((id) => cand(id)));
    expect(out.map((c) => c.candidateId)).toEqual(ids);
    expect(out.slice(0, 5).every((c) => c.score === 77)).toBe(true);
    expect(out[5].score).toBeUndefined();
  });
  it("AI 실패·상대 프로필 없음은 그 후보만 점수 없이", async () => {
    const flaky: Evaluator = async (_uid, input) => {
      if (input.overlapMinutes === 60) throw new Error("timeout");
      return fixed("me", input);
    };
    const data = new MemoryData({ me: side, ok: side, slow: side, ghost: null });
    const out = await addScores(data, flaky, "me", [cand("ok"), cand("slow", 60), cand("ghost")]);
    expect(out.map((c) => c.score)).toEqual([77, undefined, undefined]);
  });
  it("내 프로필이 없으면 AI 를 부르지 않는다", async () => {
    let calls = 0;
    const counting: Evaluator = async (u, i) => { calls++; return fixed(u, i); };
    const out = await addScores(new MemoryData({ a: side }), counting, "me", [cand("a")]);
    expect(calls).toBe(0);
    expect(out[0].score).toBeUndefined();
  });
  it("AI 입력에 UID·이름이 없고 목적 '전체'는 빈 목록", async () => {
    let seen: unknown;
    const spy: Evaluator = async (u, i) => { seen = i; return fixed(u, i); };
    await addScores(new MemoryData({ me: side, "uid-x": side }), spy, "me", [cand("uid-x")]);
    expect(JSON.stringify(seen)).not.toContain("uid-x");
    expect(seen).toMatchObject({ purposes: [], overlapMinutes: 90 });
  });
});

describe("BT-028 점수 캐시·추천순", () => {
  const live = (score = 77): Evaluator => async (u, i) => ({ ...(await fixed(u, i)), data: { score, reasons: ["태그가 같아요"], suggestedActivity: "카페" }, meta: { model: "m", requestId: "r", latencyMs: 1, mock: false } });
  const counting = (inner: Evaluator) => {
    const fn = (async (u, i) => { fn.calls++; return inner(u, i); }) as Evaluator & { calls: number };
    fn.calls = 0;
    return fn;
  };
  const now = Date.UTC(2026, 10, 19);

  it("같은 입력으로 다시 검색하면 AI 를 다시 부르지 않는다", async () => {
    const data = new MemoryData({ me: side, a: side });
    const ev = counting(live());
    await addScores(data, ev, "me", [cand("a")], now);
    const again = await addScores(data, ev, "me", [cand("a")], now + 60_000);
    expect(ev.calls).toBe(1);
    expect(again[0]).toMatchObject({ score: 77, reasons: ["태그가 같아요"], suggestedActivity: "카페" });
  });

  it("입력(겹침 30분 단위·프로필)이 바뀌거나 24시간이 지나면 다시 평가한다", async () => {
    const data = new MemoryData({ me: side, a: side });
    const ev = counting(live());
    await addScores(data, ev, "me", [cand("a", 90)], now);
    await addScores(data, ev, "me", [cand("a", 119)], now); // 90분 단위 그대로
    expect(ev.calls).toBe(1);
    await addScores(data, ev, "me", [cand("a", 120)], now);
    expect(ev.calls).toBe(2);
    await addScores(new MemoryData({ me: side, a: { ...side, tags: ["운동"] } }), ev, "me", [cand("a", 120)], now);
    expect(ev.calls).toBe(3);
    await addScores(data, ev, "me", [cand("a", 120)], now + SCORE_CACHE_TTL_MS);
    expect(ev.calls).toBe(4);
  });

  it("모델이 바뀌면 해시가 달라져 다시 평가한다 (#117 E 리뷰)", () => {
    const input = { overlapMinutes: 90, purposes: [], commonCourses: [], searcher: side, candidate: side };
    expect(inputHash(input, "gemini-a")).toBe(inputHash(input, "gemini-a"));
    expect(inputHash(input, "gemini-a")).not.toBe(inputHash(input, "gemini-b"));
  });

  it("Mock 점수는 저장하지 않고, 저장 실패해도 점수는 보여준다", async () => {
    const data = new MemoryData({ me: side, a: side });
    await addScores(data, fixed, "me", [cand("a")], now);
    expect(data.cache.size).toBe(0);
    data.failSave = true;
    const out = await addScores(data, live(), "me", [cand("a")], now);
    expect(out[0].score).toBe(77);
  });

  it("범위 밖 캐시 점수는 무시하고 다시 평가한다", async () => {
    const data = new MemoryData({ me: side, a: side });
    const ev = counting(live());
    await addScores(data, ev, "me", [cand("a")], now);
    const key = "me_a";
    data.cache.set(key, { ...data.cache.get(key)!, score: 150 });
    await addScores(data, ev, "me", [cand("a")], now);
    expect(ev.calls).toBe(2);
  });

  it("점수 높은 순, 동점·점수 없음은 기존(겹침) 순서 유지, 6번째 이후는 뒤에 그대로", async () => {
    const scores: Record<number, number> = { 180: 60, 150: 90, 120: 60 };
    const byOverlap: Evaluator = async (u, i) => ({ ...(await live()(u, i)), data: { score: scores[i.overlapMinutes], reasons: ["x"], suggestedActivity: "카페" } });
    const data = new MemoryData({ me: side, a: side, b: side, c: side, d: null, e: side, f: side });
    const list = [cand("a", 180), cand("b", 150), cand("c", 120), cand("d", 100), cand("e", 90), cand("f", 60)];
    const flaky: Evaluator = async (u, i) => { if (i.overlapMinutes === 90) throw new Error("x"); return byOverlap(u, i); };
    const out = await addScores(data, flaky, "me", list, now);
    expect(out.map((c) => c.candidateId)).toEqual(["b", "a", "c", "d", "e", "f"]);
    expect(rankByScore([{ ...cand("x"), score: 0 }, cand("y")]).map((c) => c.candidateId)).toEqual(["x", "y"]);
  });
});

describe("점수 마감 (#102 E 리뷰 1)", () => {
  it("마감까지 끝나지 않은 후보는 점수 없이, 끝난 후보는 점수와 함께 돌려준다", async () => {
    const slow: Evaluator = async (u, i) => {
      if (i.overlapMinutes === 60) await new Promise((r) => setTimeout(r, 500));
      return fixed(u, i);
    };
    const data = new MemoryData({ me: side, fast: side, slow: side });
    const out = await addScores(data, slow, "me", [cand("fast"), cand("slow", 60)], Date.now(), 50);
    expect(out.map((c) => c.score)).toEqual([77, undefined]);
    expect(SCORE_DEADLINE_MS).toBeLessThan(30_000);
  });
});

