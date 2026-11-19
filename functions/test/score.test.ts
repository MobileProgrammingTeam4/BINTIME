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
  type PublicSide,
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
