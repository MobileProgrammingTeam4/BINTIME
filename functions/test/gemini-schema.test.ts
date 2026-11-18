import { describe, expect, it } from "vitest";
import { z } from "zod";
import { toGeminiSchema } from "../src/ai/gateway";
import { aiPingTask } from "../src/ai/tasks/aiPing";
import { analyzeTimetableTask, MAX_IMAGES } from "../src/ai/tasks/analyzeTimetable";
import { evaluateMatchTask } from "../src/ai/tasks/evaluateMatch";
import { generateProfileTask } from "../src/ai/tasks/generateProfile";
import { recommendPlaceTask } from "../src/ai/tasks/recommendPlace";
import { suggestChatRepliesTask } from "../src/ai/tasks/suggestChatReplies";
import type { AiTask } from "../src/ai/types";

/** 게이트웨이가 Gemini 에 보내는 스키마(callModel 과 같은 호출) */
const sent = (task: AiTask<any, any>) => toGeminiSchema(task.output, task.geminiSchema);

const CONSTRAINT_KEYS = ["minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
  "minLength", "maxLength", "pattern", "format", "minItems", "maxItems"];

/** 스키마 노드에 남은 값 제약 키워드 경로. properties 의 필드 이름은 제외한다. */
function constraintPaths(node: unknown, at = "$"): string[] {
  if (!node || typeof node !== "object") return [];
  const schema = node as Record<string, any>;
  const found = CONSTRAINT_KEYS.filter((k) => k in schema).map((k) => `${at}.${k}`);
  for (const [name, child] of Object.entries(schema.properties ?? {})) found.push(...constraintPaths(child, `${at}.${name}`));
  if (schema.items) found.push(...constraintPaths(schema.items, `${at}[]`));
  (schema.anyOf ?? []).forEach((child: unknown, i: number) => found.push(...constraintPaths(child, `${at}|${i}`)));
  return found;
}

describe("Gemini 응답 스키마: 기본(full)은 이번 변경 전과 같다", () => {
  // 변경 전 develop(c757354)의 toGeminiSchema 출력 그대로
  const before: Array<[string, AiTask<any, any>, unknown]> = [
    ["aiPing", aiPingTask, {"type":"object","properties":{"reply":{"type":"string","minLength":1,"maxLength":200}},"required":["reply"],"additionalProperties":false}],
    ["evaluateMatch", evaluateMatchTask, {"type":"object","properties":{"score":{"type":"integer","minimum":0,"maximum":100},"reasons":{"minItems":1,"maxItems":3,"type":"array","items":{"type":"string","minLength":1,"maxLength":80}},"suggestedActivity":{"type":"string","minLength":1,"maxLength":40}},"required":["score","reasons","suggestedActivity"],"additionalProperties":false}],
    ["generateProfile", generateProfileTask, {"type":"object","properties":{"bio":{"type":"string","minLength":1,"maxLength":150},"suggestedTags":{"maxItems":5,"type":"array","items":{"type":"string","minLength":1,"maxLength":20}}},"required":["bio","suggestedTags"],"additionalProperties":false}],
    ["recommendPlace", recommendPlaceTask, {"type":"object","properties":{"places":{"minItems":1,"maxItems":3,"type":"array","items":{"type":"object","properties":{"placeId":{"type":"string","minLength":1,"maxLength":64},"name":{"type":"string","minLength":1,"maxLength":60},"reason":{"type":"string","minLength":1,"maxLength":80}},"required":["placeId","name","reason"],"additionalProperties":false}}},"required":["places"],"additionalProperties":false}],
    ["suggestChatReplies", suggestChatRepliesTask, {"type":"object","properties":{"replies":{"minItems":3,"maxItems":3,"type":"array","items":{"type":"string","minLength":1,"maxLength":50}}},"required":["replies"],"additionalProperties":false}],
  ];

  it.each(before)("%s 는 옵션을 켜지 않았고 제약까지 그대로 보낸다", (_name, task, expected) => {
    expect(task.geminiSchema).toBeUndefined();
    expect(sent(task)).toEqual(expected);
  });

  it("mode 를 생략하면 full 과 같다", () => {
    expect(toGeminiSchema(analyzeTimetableTask.output)).toEqual(toGeminiSchema(analyzeTimetableTask.output, "full"));
  });
});

describe("Gemini 응답 스키마: analyzeTimetable 은 typesOnly", () => {
  it("범위·길이·개수 제약이 하나도 없다(400 too many states 방지)", () => {
    expect(analyzeTimetableTask.geminiSchema).toBe("typesOnly");
    const schema = sent(analyzeTimetableTask);
    expect(constraintPaths(schema)).toEqual([]);
    // full 에는 있던 제약이다(검사가 헛돌지 않는지)
    expect(constraintPaths(toGeminiSchema(analyzeTimetableTask.output, "full")).length).toBeGreaterThan(10);
  });

  it("구조(타입·필수 필드·null 허용·추가 필드 금지)는 그대로다", () => {
    expect(sent(analyzeTimetableTask)).toEqual({
      type: "object",
      properties: {
        courses: {
          type: "array",
          items: {
            type: "object",
            properties: {
              name: { type: "string" },
              dayOfWeek: { type: "integer" },
              startMinute: { type: "integer" },
              endMinute: { type: "integer" },
              building: { anyOf: [{ type: "string" }, { type: "null" }] },
              room: { anyOf: [{ type: "string" }, { type: "null" }] },
              confidence: { type: "number" },
              sourceImageIndex: { type: "integer" },
            },
            required: ["name", "dayOfWeek", "startMinute", "endMinute", "building", "room", "confidence", "sourceImageIndex"],
            additionalProperties: false,
          },
        },
        warnings: { type: "array", items: { type: "string" } },
      },
      required: ["courses", "warnings"],
      additionalProperties: false,
    });
  });

  it("서버 검사(output 스키마)는 제약을 그대로 가진다: sourceImageIndex 는 MAX_IMAGES - 1 까지", () => {
    const course = { name: "a", dayOfWeek: 1, startMinute: 540, endMinute: 600, building: null, room: null, confidence: 0.5 };
    const parse = (sourceImageIndex: number) =>
      analyzeTimetableTask.output.safeParse({ courses: [{ ...course, sourceImageIndex }], warnings: [] }).success;
    expect(parse(MAX_IMAGES - 1)).toBe(true);
    expect(parse(MAX_IMAGES)).toBe(false);
    expect(analyzeTimetableTask.output.safeParse({ courses: [{ ...course, sourceImageIndex: 0, dayOfWeek: 8 }], warnings: [] }).success)
      .toBe(false);
  });
});

describe("toGeminiSchema typesOnly 처리 범위", () => {
  it("필드 이름이 제약 키워드와 같아도 지우지 않고, 중첩·anyOf·additionalProperties 안의 제약은 지운다", () => {
    const schema = z.object({
      maximum: z.number().max(3),
      list: z.array(z.object({ pattern: z.string().regex(/^a/).max(5) })).min(1).max(2),
      either: z.union([z.string().min(2), z.number().int().min(0)]),
      dict: z.record(z.string(), z.string().max(4)),
    }).strict();
    const out = toGeminiSchema(schema, "typesOnly") as any;
    expect(Object.keys(out.properties).sort()).toEqual(["dict", "either", "list", "maximum"]);
    expect(out.properties.list.items.properties).toHaveProperty("pattern");
    expect(constraintPaths(out)).toEqual([]);
    expect(JSON.stringify(out.properties.dict.additionalProperties)).not.toMatch(/maxLength/);
    expect(out.additionalProperties).toBe(false);
  });
});
