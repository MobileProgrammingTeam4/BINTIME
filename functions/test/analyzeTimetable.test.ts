import { describe, expect, it } from "vitest";
import { runAiTask } from "../src/ai/gateway";
import { AiError } from "../src/ai/errors";
import {
  MAX_IMAGE_BYTES,
  MAX_TOTAL_IMAGE_BYTES,
  PROMPT,
  analyzeTimetableTask,
  toImageReadError,
} from "../src/ai/tasks/analyzeTimetable";
import type { StoredImage } from "../src/ai/types";
import { ctx, expectReason, makeDeps, type Step } from "./helpers";

/** C(BT-026) analyzeTimetable live 구현: 경로·파일 검사(횟수 차감 전), 이미지 캐시, 요청 본문, 출력 검사. */

const me = "uid-me";
const batch = `timetableImages/${me}/batch-1`;
const path = (i: number) => `${batch}/${i}.jpg`;

const jpeg = (size = 16, fill = 1) => Buffer.concat([Buffer.from([0xff, 0xd8, 0xff]), Buffer.alloc(size - 3, fill)]);
const image = (bytes: Buffer = jpeg(), contentType: string | undefined = "image/jpeg"): StoredImage => ({
  contentType,
  size: bytes.length,
  bytes,
});
/** 크기만 있는 메타데이터(내려받지 않음 확인용). */
const sized = (size: number): StoredImage => ({ contentType: "image/jpeg", size });

const course = (overrides: Record<string, unknown> = {}) => ({
  name: "가나다 과목",
  dayOfWeek: 1,
  startMinute: 540,
  endMinute: 615,
  building: null,
  room: "A101",
  confidence: 0.9,
  sourceImageIndex: 0,
  ...overrides,
});
const ok = (courses: unknown[] = [course()], warnings: string[] = []) => ({ text: JSON.stringify({ courses, warnings }) });

function liveDeps(images: Record<string, StoredImage | Error>, steps: Step[] = []) {
  const deps = makeDeps({ mode: "live", steps });
  const reads: Array<{ path: string; maxBytes: number }> = [];
  let downloads = 0;
  deps.readImage = async (p, maxBytes) => {
    reads.push({ path: p, maxBytes });
    const value = images[p];
    if (value instanceof Error) throw value;
    if (!value) throw Object.assign(new Error("No such object"), { code: 404 });
    // 실제 구현처럼 상한을 넘으면 내려받지 않는다.
    if (value.size > maxBytes) return { contentType: value.contentType, size: value.size };
    if (value.bytes) downloads++;
    return value;
  };
  return Object.assign(deps, { reads, downloads: () => downloads });
}

const run = (imagePaths: string[], deps: ReturnType<typeof liveDeps>) =>
  runAiTask(analyzeTimetableTask, { imagePaths }, ctx(me), deps);

/** 실패가 횟수 차감과 Gemini 호출 전에 났는지 */
function expectNothingSpent(deps: ReturnType<typeof liveDeps>) {
  expect(deps.usageStore.counts.size).toBe(0);
  expect(deps.client.calls).toHaveLength(0);
}

describe("analyzeTimetable 경로 검사(모든 모드)", () => {
  it("형식이 틀린 경로는 INVALID_INPUT, 위치를 알려준다", async () => {
    for (const bad of [`${batch}/5.jpg`, `${batch}/0.png`, `${batch}/a.jpg`, `timetableImages/${me}/0.jpg`,
      `timetableImages/${me}/bad_batch/0.jpg`, `${batch}/sub/0.jpg`]) {
      const error = await expectReason(
        runAiTask(analyzeTimetableTask, { imagePaths: [path(0), bad] }, ctx(me), makeDeps()), "INVALID_INPUT");
      expect(error.fields).toEqual(["imagePaths.1"]);
    }
  });

  it("서로 다른 묶음이나 같은 경로 중복은 INVALID_INPUT", async () => {
    await expectReason(runAiTask(analyzeTimetableTask,
      { imagePaths: [path(0), `timetableImages/${me}/batch-2/1.jpg`] }, ctx(me), makeDeps()), "INVALID_INPUT");
    await expectReason(runAiTask(analyzeTimetableTask,
      { imagePaths: [path(0), path(0)] }, ctx(me), makeDeps()), "INVALID_INPUT");
  });

  it("다른 사용자 경로는 형식이 맞아도 PERMISSION_DENIED", async () => {
    await expectReason(runAiTask(analyzeTimetableTask,
      { imagePaths: ["timetableImages/uid-other/batch-1/0.jpg"] }, ctx(me), makeDeps()), "PERMISSION_DENIED");
  });

  it("mock 모드는 Storage 를 읽지 않는다", async () => {
    const deps = makeDeps();
    let reads = 0;
    deps.readImage = async () => {
      reads++;
      throw new Error("should not read");
    };
    const res = await runAiTask(analyzeTimetableTask, { imagePaths: [path(0), path(1)] }, ctx(me), deps);
    expect(res.meta.mock).toBe(true);
    expect(reads).toBe(0);
  });
});

describe("analyzeTimetable live: 파일 검사는 횟수 차감 전에 끝난다", () => {
  it("없는 파일(404)은 INVALID_INPUT", async () => {
    const deps = liveDeps({ [path(0)]: image() });
    const error = await expectReason(run([path(0), path(1)], deps), "INVALID_INPUT");
    expect(error.fields).toEqual(["imagePaths.1"]);
    expect(error.retryable).toBe(false);
    expectNothingSpent(deps);
  });

  it("contentType 이 image/jpeg 가 아니거나 없으면 INVALID_INPUT", async () => {
    for (const type of ["image/png", undefined]) {
      const deps = liveDeps({ [path(0)]: { ...image(), contentType: type } });
      await expectReason(run([path(0)], deps), "INVALID_INPUT");
      expectNothingSpent(deps);
    }
  });

  it("JPEG 시그니처(FF D8 FF)가 아니면 INVALID_INPUT", async () => {
    const deps = liveDeps({ [path(0)]: image(Buffer.from("not a jpeg at all")) });
    await expectReason(run([path(0)], deps), "INVALID_INPUT");
    expectNothingSpent(deps);
  });

  it("한 장이 5MB 이상이면 내려받지 않고 INVALID_INPUT", async () => {
    const deps = liveDeps({ [path(0)]: sized(MAX_IMAGE_BYTES) });
    const error = await expectReason(run([path(0)], deps), "INVALID_INPUT");
    expect(error.fields).toEqual(["imagePaths.0"]);
    expect(deps.reads[0].maxBytes).toBe(MAX_IMAGE_BYTES - 1);
    expect(deps.downloads()).toBe(0);
    expectNothingSpent(deps);
  });

  it("메타데이터와 달리 내려받은 내용이 5MB 이상이어도 INVALID_INPUT", async () => {
    const big = jpeg(MAX_IMAGE_BYTES);
    const deps = liveDeps({ [path(0)]: { contentType: "image/jpeg", size: 10, bytes: big } });
    await expectReason(run([path(0)], deps), "INVALID_INPUT");
    expectNothingSpent(deps);
  });

  it("전체 원본이 14MiB 를 넘으면 INVALID_INPUT, 정확히 14MiB 는 통과", async () => {
    const per = MAX_TOTAL_IMAGE_BYTES / 4; // 3.5MiB × 4 = 14MiB
    const four = { [path(0)]: image(jpeg(per)), [path(1)]: image(jpeg(per)), [path(2)]: image(jpeg(per)), [path(3)]: image(jpeg(per)) };
    const exact = liveDeps(four, [ok()]);
    await run([path(0), path(1), path(2), path(3)], exact);

    const over = liveDeps({ ...four, [path(4)]: image(jpeg(16)) });
    const error = await expectReason(run([path(0), path(1), path(2), path(3), path(4)], over), "INVALID_INPUT");
    expect(error.fields).toEqual(["imagePaths"]);
    expectNothingSpent(over);
  });

  it("Storage 오류는 AiError 로 바뀌고 횟수를 쓰지 않는다", async () => {
    const cases: Array<[unknown, string, boolean]> = [
      [Object.assign(new Error("x"), { code: 403 }), "AI_UNAVAILABLE", false],
      [Object.assign(new Error("x"), { code: 503 }), "AI_UNAVAILABLE", true],
      [Object.assign(new Error("x"), { code: "ECONNRESET" }), "AI_UNAVAILABLE", true],
      [new Error("strange"), "INTERNAL", false],
    ];
    for (const [thrown, reason, retryable] of cases) {
      const deps = liveDeps({ [path(0)]: thrown as Error });
      const error = await expectReason(run([path(0)], deps), reason);
      expect(error.retryable).toBe(retryable);
      expectNothingSpent(deps);
    }
  });
});

describe("toImageReadError", () => {
  it("코드별 변환", () => {
    const of = (code: unknown) => toImageReadError(Object.assign(new Error("x"), { code }), 2);
    expect(of(404).reason).toBe("INVALID_INPUT");
    expect(of(404).fields).toEqual(["imagePaths.2"]);
    expect([of(401).reason, of(401).retryable]).toEqual(["AI_UNAVAILABLE", false]);
    expect([of(403).reason, of(403).retryable]).toEqual(["AI_UNAVAILABLE", false]);
    expect([of(429).reason, of(429).retryable]).toEqual(["AI_UNAVAILABLE", true]);
    expect([of(500).reason, of(500).retryable]).toEqual(["AI_UNAVAILABLE", true]);
    for (const net of ["ETIMEDOUT", "EAI_AGAIN", "ENOTFOUND"]) expect(of(net).reason).toBe("AI_UNAVAILABLE");
    expect(of(400).reason).toBe("INTERNAL");
    expect(toImageReadError("not an error", 0).reason).toBe("INTERNAL");
    expect(toImageReadError(null, 0).reason).toBe("INTERNAL");
    const already = new AiError("INVALID_INPUT");
    expect(toImageReadError(already, 0)).toBe(already);
  });
});

describe("analyzeTimetable live: 요청 본문", () => {
  it("이미지 번호와 inlineData(image/jpeg, base64)를 순서대로 보내고 경로·UID 는 보내지 않는다", async () => {
    const a = jpeg(32, 7);
    const b = jpeg(48, 9);
    const deps = liveDeps({ [path(0)]: image(a), [path(1)]: image(b) }, [ok([course(), course({ sourceImageIndex: 1 })])]);
    const res = await run([path(0), path(1)], deps);
    expect(res.data.courses).toHaveLength(2);

    const call = deps.client.calls[0];
    expect(call.systemInstruction).toBe(PROMPT);
    expect(call.temperature).toBe(0);
    expect(call.maxOutputTokens).toBe(8192);
    expect(call.timeoutMs).toBe(30_000);
    const contents = call.contents as Array<{ role: string; parts: Array<Record<string, any>> }>;
    expect(contents).toHaveLength(1);
    expect(contents[0].role).toBe("user");
    const parts = contents[0].parts;
    expect(parts).toHaveLength(5);
    expect(parts[0].text).toContain("2장");
    expect(parts[1]).toEqual({ text: "이미지 0:" });
    expect(parts[2]).toEqual({ inlineData: { mimeType: "image/jpeg", data: a.toString("base64") } });
    expect(parts[3]).toEqual({ text: "이미지 1:" });
    expect(parts[4]).toEqual({ inlineData: { mimeType: "image/jpeg", data: b.toString("base64") } });

    const serialized = JSON.stringify(call.contents) + PROMPT;
    for (const secret of [me, "batch-1", "timetableImages"]) expect(serialized).not.toContain(secret);
  });

  it("응답 스키마는 구조화 출력으로 보내고 수업 항목에 warnings 가 없다", async () => {
    const deps = liveDeps({ [path(0)]: image() }, [ok()]);
    await run([path(0)], deps);
    const schema = deps.client.calls[0].jsonSchema as any;
    const item = schema.properties.courses.items;
    expect(item.additionalProperties).toBe(false);
    expect(Object.keys(item.properties).sort()).toEqual(
      ["building", "confidence", "dayOfWeek", "endMinute", "name", "room", "sourceImageIndex", "startMinute"]);
    expect(schema.properties.warnings).toBeDefined();
    // 게이트웨이가 typesOnly 로 보낸다: 값 제약이 있으면 Gemini 가 400 too many states 로 거부한다
    expect(JSON.stringify(schema)).not.toMatch(/"(minimum|maximum|minLength|maxLength|minItems|maxItems)"/);
  });

  it("이미지는 한 번만 읽는다(형식 오류로 재시도해도 다시 읽지 않는다)", async () => {
    const deps = liveDeps({ [path(0)]: image(), [path(1)]: image() }, [{ text: "not json" }, ok()]);
    await run([path(0), path(1)], deps);
    expect(deps.reads.map((r) => r.path)).toEqual([path(0), path(1)]);
    expect(deps.client.calls).toHaveLength(2);
    expect(deps.client.calls[1].contents).toEqual(deps.client.calls[0].contents);
    expect([...deps.usageStore.counts.values()]).toEqual([1]);
  });

  it("authorize 를 거치지 않고 buildRequest 를 부르면 같은 검사를 거쳐 읽는다", async () => {
    const deps = liveDeps({ [path(0)]: image() });
    const req = await analyzeTimetableTask.buildRequest!({ imagePaths: [path(0)] }, ctx(me), deps);
    expect(deps.reads).toHaveLength(1);
    expect(JSON.stringify(req.contents)).toContain("inlineData");
    await expectReason(analyzeTimetableTask.buildRequest!({ imagePaths: ["timetableImages/uid-other/batch-1/0.jpg"] },
      ctx(me), deps), "PERMISSION_DENIED");
  });
});

describe("analyzeTimetable live: 출력 검사", () => {
  const bad: Array<[string, unknown]> = [
    ["수업별 warnings", course({ warnings: ["x"] })],
    ["시작 >= 종료", course({ startMinute: 600, endMinute: 600 })],
    ["없는 이미지 번호", course({ sourceImageIndex: 1 })],
    ["building 키 누락", (({ building: _b, ...rest }) => rest)(course())],
    ["요일 범위 밖", course({ dayOfWeek: 8 })],
    ["신뢰도 범위 밖", course({ confidence: 1.2 })],
  ];
  it.each(bad)("%s → 재시도 1회 후 AI_INVALID_OUTPUT", async (_label, wrong) => {
    const deps = liveDeps({ [path(0)]: image() }, [ok([wrong]), ok([wrong])]);
    await expectReason(run([path(0)], deps), "AI_INVALID_OUTPUT");
    expect(deps.client.calls).toHaveLength(2);
  });

  it("수업 61개는 AI_INVALID_OUTPUT", async () => {
    const many = Array.from({ length: 61 }, () => course());
    const deps = liveDeps({ [path(0)]: image() }, [ok(many), ok(many)]);
    await expectReason(run([path(0)], deps), "AI_INVALID_OUTPUT");
  });

  it("형식 오류 뒤 정상 응답이면 성공, 빈 결과와 안내도 통과", async () => {
    const deps = liveDeps({ [path(0)]: image() }, [ok([course({ dayOfWeek: 0 })]), ok([], ["시간표를 찾을 수 없습니다."])]);
    const res = await run([path(0)], deps);
    expect(res.data).toEqual({ courses: [], warnings: ["시간표를 찾을 수 없습니다."] });
  });

  it("하루 10회 뒤 11번째는 QUOTA_EXCEEDED(이미지는 차감 전에 읽힌다)", async () => {
    const steps = Array.from({ length: 10 }, () => ok());
    const deps = liveDeps({ [path(0)]: image() }, steps);
    for (let i = 0; i < 10; i++) await run([path(0)], deps);
    await expectReason(run([path(0)], deps), "QUOTA_EXCEEDED");
    expect(deps.reads).toHaveLength(11);
    expect(deps.client.calls).toHaveLength(10);
  });
});
