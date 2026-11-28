import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { toGeminiSchema } from "../src/ai/gateway";
import { PROMPT, analyzeTimetableTask } from "../src/ai/tasks/analyzeTimetable";
// @ts-ignore 스크립트는 .mjs(JS)라 타입 선언이 없다
import { callOnce, resolutionOption, thinkingConfig } from "../scripts/eval-analyze-timetable.mjs";
// @ts-ignore
import { aggregate, countOffFiveMinute, deltaHistogram, formatSummary, imageKey, isMatch, matchCourses, scoreImage } from "../scripts/eval-scoring.mjs";

/** BT-048 평가 스크립트: 채점·매칭·집계와 요청 구성. 실제 API 는 호출하지 않는다(가짜 ai 객체). */

const t = (dayOfWeek: number, startMinute: number, endMinute: number, extra: Record<string, unknown> = {}) =>
  ({ name: "C1", dayOfWeek, startMinute, endMinute, ...extra });
const r = (dayOfWeek: number, startMinute: number, endMinute: number, confidence = 0.9) =>
  ({ name: "실제 과목", dayOfWeek, startMinute, endMinute, confidence });

describe("imageKey", () => {
  it("파일명 IMG_숫자.jpeg/jpg 를 대문자 키로, 그 밖은 null", () => {
    expect(imageKey("IMG_3466.jpeg")).toBe("IMG_3466");
    expect(imageKey("img_3448.JPG")).toBe("IMG_3448");
    expect(imageKey("IMG_3466.png")).toBeNull();
    expect(imageKey("photo.jpeg")).toBeNull();
  });
});

describe("isMatch", () => {
  it("요일·시작·종료가 모두 같아야 일치", () => {
    expect(isMatch(t(1, 810, 900), r(1, 810, 900))).toBe(true);
    expect(isMatch(t(1, 810, 900), r(3, 810, 900))).toBe(false);
    expect(isMatch(t(1, 810, 900), r(1, 780, 900))).toBe(false);
    expect(isMatch(t(1, 810, 900), r(1, 810, 870))).toBe(false);
  });

  it("endApprox 는 종료만 ±30분 허용(경계 포함), 시작은 정확해야 한다", () => {
    const approx = t(2, 1020, 1130, { endApprox: true });
    for (const end of [1100, 1110, 1140, 1160]) expect(isMatch(approx, r(2, 1020, end))).toBe(true);
    for (const end of [1099, 1161, 1200]) expect(isMatch(approx, r(2, 1020, end))).toBe(false);
    expect(isMatch(approx, r(2, 990, 1140))).toBe(false);
  });
});

describe("matchCourses", () => {
  it("일치를 먼저 묶고, 남은 것은 요일별 시작 순서로 짝지어 어긋난 분을 잰다", () => {
    const truth = [t(1, 810, 900), t(1, 900, 990), t(2, 540, 630)];
    const rec = [r(1, 840, 930), r(2, 540, 630), r(1, 780, 870)];
    const m = matchCourses(truth, rec);
    expect(m.matched).toHaveLength(1);
    expect(m.matched[0].truth).toEqual(t(2, 540, 630));
    expect(m.mismatched.map((x: any) => [x.truth.startMinute, x.startDelta, x.endDelta])).toEqual([[810, -30, -30], [900, -60, -60]]);
    expect(m.missing).toEqual([]);
    expect(m.extra).toEqual([]);
  });

  it("다른 요일끼리는 짝짓지 않고 누락·초과로 남긴다", () => {
    const m = matchCourses([t(1, 540, 600)], [r(2, 540, 600)]);
    expect(m.matched).toHaveLength(0);
    expect(m.mismatched).toHaveLength(0);
    expect(m.missing).toEqual([t(1, 540, 600)]);
    expect(m.extra).toEqual([r(2, 540, 600)]);
  });

  it("같은 시각 수업이 반복돼도(같은 요일 두 번) 한 인식을 두 번 쓰지 않는다", () => {
    const m = matchCourses([t(1, 540, 600), t(1, 540, 600)], [r(1, 540, 600)]);
    expect(m.matched).toHaveLength(1);
    expect(m.missing).toHaveLength(1);
  });

  it("수업명은 비교하지 않는다(가명 C1 과 실제 이름)", () => {
    expect(matchCourses([t(5, 660, 780)], [{ ...r(5, 660, 780), name: "완전히 다른 이름" }]).matched).toHaveLength(1);
  });
});

describe("집계 보조", () => {
  it("deltaHistogram 은 0 을 빼고 부호를 붙여 정렬한다", () => {
    expect(deltaHistogram([-30, 0, -30, 60, -60])).toEqual({ "-60": 1, "-30": 2, "+60": 1 });
    expect(Object.keys(deltaHistogram([60, -30, 30]))).toEqual(["-30", "+30", "+60"]);
  });

  it("countOffFiveMinute 는 시작·종료 값마다 센다", () => {
    expect(countOffFiveMinute([r(1, 810, 900), r(1, 812, 903), r(1, 815, 1129)])).toBe(3);
  });
});

describe("scoreImage", () => {
  it("confidence 비율: 맞은 수업 중 0.7 미만, 틀린 수업(어긋남+초과) 중 0.7 이상", () => {
    const truth = [t(1, 540, 630), t(1, 630, 720), t(2, 540, 630)];
    const rec = [r(1, 540, 630, 0.5), r(1, 600, 720, 0.95), r(2, 540, 630, 0.9), r(3, 540, 600, 0.4)];
    const s = scoreImage(truth, rec);
    expect(s.matchedCount).toBe(2);
    expect(s.lowConfidenceAmongMatched).toBe(0.5);
    expect(s.wrongCount).toBe(2);
    expect(s.highConfidenceAmongWrong).toBe(0.5);
    expect(s.startDeltas).toEqual({ "-30": 1 });
    expect(s.endDeltas).toEqual({});
    expect(s.extraCount).toBe(1);
  });

  it("2026-11-24 IMG_3466 기준선을 재현한다(12개 중 9개, 종료 -60 두 건, +30 한 건)", () => {
    const fixture = JSON.parse(readFileSync(join(__dirname, "fixtures", "timetable-truth.json"), "utf8"));
    const truth = fixture.images.find((i: any) => i.image === "IMG_3466").courses;
    const baseline = [
      r(1, 540, 660), r(2, 720, 810), r(4, 720, 810), r(2, 810, 900), r(4, 810, 900), r(2, 900, 990), r(4, 900, 990),
      r(2, 990, 1080), r(4, 990, 1080), r(5, 660, 720), r(5, 840, 930), r(7, 540, 600),
    ];
    const s = scoreImage(truth, baseline);
    expect([s.truthCount, s.recognizedCount, s.matchedCount]).toEqual([12, 12, 9]);
    expect(s.startDeltas).toEqual({});
    expect(s.endDeltas).toEqual({ "-60": 2, "+30": 1 });
    expect(s.highConfidenceAmongWrong).toBe(1);
  });

  it("빈 결과면 비율은 null", () => {
    const s = scoreImage([t(1, 540, 600)], []);
    expect(s.lowConfidenceAmongMatched).toBeNull();
    expect(s.highConfidenceAmongWrong).toBeNull();
    expect(s.missingCount).toBe(1);
  });
});

describe("aggregate·formatSummary", () => {
  it("이미지·반복 합계와 실패 호출, 시간·토큰을 모은다", () => {
    const a = scoreImage([t(1, 540, 630)], [r(1, 510, 600)]);
    const b = scoreImage([t(2, 540, 630)], [r(2, 540, 630, 0.6)]);
    const total = aggregate([
      { score: a, seconds: 10, promptTokens: 1000, outputTokens: 100 },
      { score: b, seconds: 14, promptTokens: 1100, outputTokens: 120 },
      { seconds: 3 },
    ]);
    expect(total.calls).toBe(3);
    expect(total.failedCalls).toBe(1);
    expect(total.truthCount).toBe(2);
    expect(total.matchedCount).toBe(1);
    expect(total.startDeltas).toEqual({ "-30": 1 });
    expect(total.endDeltas).toEqual({ "-30": 1 });
    expect(total.seconds).toEqual({ total: 27, max: 14, mean: 9 });
    expect([total.promptTokens, total.outputTokens]).toEqual([2100, 220]);
    expect(total.lowConfidenceAmongMatched).toBe(1);
    expect(total.highConfidenceAmongWrong).toBe(1);
  });

  it("요약은 표·박스 문자 없이 장별 줄과 합계를 쓴다", () => {
    const score = scoreImage([t(1, 540, 630)], [r(1, 540, 630)]);
    const text = formatSummary({
      config: { model: "m", thinking: "low", resolution: "HIGH", repeat: 1 },
      images: [{ image: "IMG_1", runs: [{ repeat: 1, score, seconds: 9.5, promptTokens: 10, outputTokens: 5 }] },
        { image: "IMG_2", runs: [{ repeat: 1, seconds: 2, error: { kind: "API", message: "400" } }] }],
      total: aggregate([{ score, seconds: 9.5 }, { seconds: 2 }]),
    });
    expect(text).toContain("IMG_1 (반복 1): 정답/인식/일치 1/1/1, 9.5초");
    expect(text).toContain("IMG_2 (반복 1): 실패 API 400");
    expect(text).toContain("실패한 호출 1/2");
    expect(text).not.toMatch(/[─-╿|]/);
  });
});

describe("옵션 변환", () => {
  it("--thinking", () => {
    expect(thinkingConfig("default")).toBeUndefined();
    expect(thinkingConfig("off")).toEqual({ thinkingBudget: 0 });
    expect(thinkingConfig("Low")).toEqual({ thinkingLevel: "LOW" });
    expect(thinkingConfig("1024")).toEqual({ thinkingBudget: 1024 });
    expect(() => thinkingConfig("max")).toThrow();
  });

  it("--resolution: LOW~HIGH 는 요청 설정, ULTRA 는 이미지 part 단위", () => {
    expect(resolutionOption("default")).toEqual({});
    expect(resolutionOption("high")).toEqual({ config: "MEDIA_RESOLUTION_HIGH" });
    expect(resolutionOption("ULTRA")).toEqual({ part: "MEDIA_RESOLUTION_ULTRA_HIGH" });
    expect(() => resolutionOption("4K")).toThrow();
  });
});

describe("callOnce(가짜 ai)", () => {
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff]), Buffer.alloc(13, 1)]);
  const fakeAi = (text: string) => {
    const calls: any[] = [];
    return { calls, models: { generateContent: async (req: any) => {
      calls.push(req);
      return { text, modelVersion: "fake", usageMetadata: { promptTokenCount: 1200, candidatesTokenCount: 300 } };
    } } };
  };
  const options = (extra: Record<string, unknown> = {}) =>
    ({ model: "m", thinkingConfig: undefined, resolutionOption: {}, ...extra });
  const course = { name: "가", dayOfWeek: 1, startMinute: 540, endMinute: 630, building: null, room: null, confidence: 0.9, sourceImageIndex: 0 };

  it("앱과 같은 요청(PROMPT·temperature 0·16384·typesOnly 스키마·이미지 part)을 보내고 응답을 스키마·verify 로 검사한다", async () => {
    const ai = fakeAi(JSON.stringify({ courses: [course], warnings: [] }));
    const rec = await callOnce({ ai, task: analyzeTimetableTask, toGeminiSchema, options: options(), bytes: jpeg, secret: "k" });
    expect(rec.error).toBeUndefined();
    expect(rec.output.courses).toEqual([course]);
    expect([rec.promptTokens, rec.outputTokens]).toEqual([1200, 300]);
    const req = ai.calls[0];
    expect(req.model).toBe("m");
    expect(req.config.systemInstruction).toBe(PROMPT);
    expect(req.config.temperature).toBe(0);
    expect(req.config.maxOutputTokens).toBe(16_384);
    expect(req.config.responseJsonSchema).toEqual(toGeminiSchema(analyzeTimetableTask.output, "typesOnly"));
    expect(req.config.thinkingConfig).toBeUndefined();
    expect(req.config.mediaResolution).toBeUndefined();
    const parts = req.contents[0].parts;
    expect(parts[1]).toEqual({ text: "이미지 0:" });
    expect(parts[2]).toEqual({ inlineData: { mimeType: "image/jpeg", data: jpeg.toString("base64") } });
  });

  it("thinking·resolution 옵션을 싣는다(ULTRA 는 inlineData part 에)", async () => {
    const ai = fakeAi(JSON.stringify({ courses: [], warnings: [] }));
    await callOnce({ ai, task: analyzeTimetableTask, toGeminiSchema, bytes: jpeg, secret: "k",
      options: options({ thinkingConfig: { thinkingLevel: "LOW" }, resolutionOption: { config: "MEDIA_RESOLUTION_HIGH" } }) });
    await callOnce({ ai, task: analyzeTimetableTask, toGeminiSchema, bytes: jpeg, secret: "k",
      options: options({ resolutionOption: { part: "MEDIA_RESOLUTION_ULTRA_HIGH" } }) });
    expect(ai.calls[0].config.thinkingConfig).toEqual({ thinkingLevel: "LOW" });
    expect(ai.calls[0].config.mediaResolution).toBe("MEDIA_RESOLUTION_HIGH");
    expect(ai.calls[1].config.mediaResolution).toBeUndefined();
    expect(ai.calls[1].contents[0].parts[2].mediaResolution).toEqual({ level: "MEDIA_RESOLUTION_ULTRA_HIGH" });
  });

  it("JSON 아님·스키마 위반·verify 위반·API 오류를 구분해 기록하고, 오류 문구에서 키를 지운다", async () => {
    const run = (ai: any) => callOnce({ ai, task: analyzeTimetableTask, toGeminiSchema, options: options(), bytes: jpeg, secret: "SECRET-KEY" });
    expect((await run(fakeAi("not json"))).error.kind).toBe("INVALID_JSON");
    expect((await run(fakeAi(JSON.stringify({ courses: [{ ...course, startMinute: 2000 }], warnings: [] })))).error.kind).toBe("SCHEMA");
    expect((await run(fakeAi(JSON.stringify({ courses: [{ ...course, startMinute: 630 }], warnings: [] })))).error.kind).toBe("VERIFY");
    const failing = { models: { generateContent: async () => {
      throw Object.assign(new Error("bad request key=SECRET-KEY"), { status: 400 });
    } } };
    const rec = await run(failing);
    expect(rec.error).toMatchObject({ kind: "API", status: 400 });
    expect(rec.error.message).not.toContain("SECRET-KEY");
  });
});
