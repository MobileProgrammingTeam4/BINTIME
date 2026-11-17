import { describe, expect, it } from "vitest";
import { evaluateMatch } from "../src/ai/internal";
import { runAiTask } from "../src/ai/gateway";
import { aiPingTask } from "../src/ai/tasks/aiPing";
import { analyzeTimetableTask } from "../src/ai/tasks/analyzeTimetable";
import { evaluateMatchTask } from "../src/ai/tasks/evaluateMatch";
import { generateProfileTask } from "../src/ai/tasks/generateProfile";
import { recommendPlaceTask } from "../src/ai/tasks/recommendPlace";
import { suggestChatRepliesTask } from "../src/ai/tasks/suggestChatReplies";
import type { AiTask } from "../src/ai/types";
import { ctx, expectReason, makeDeps } from "./helpers";

const me = "uid-me";
const matches = { "match-1": [me, "uid-other"] };

const validInputs: Array<[AiTask<any, any>, unknown]> = [
  [aiPingTask, {}],
  [analyzeTimetableTask, { imagePaths: [`timetableImages/${me}/a.jpg`, `timetableImages/${me}/b.jpg`] }],
  [generateProfileTask, { department: "소프트웨어학과", grade: 2, preferredActivities: ["스터디"] }],
  [
    evaluateMatchTask,
    {
      overlapMinutes: 90,
      purposes: ["스터디"],
      commonCourses: ["자료구조"],
      searcher: { department: "소프트웨어학과", grade: 2, tags: ["#카페", "#스터디"] },
      candidate: { department: "컴퓨터공학과", grade: 3, tags: ["#카페"] },
    },
  ],
  [recommendPlaceTask, { matchId: "match-1" }],
  [suggestChatRepliesTask, { matchId: "match-1" }],
];

describe("Mock 응답이 각 작업의 계약을 지킨다", () => {
  it.each(validInputs.map(([task, input]) => [task.name, task, input] as const))("%s", async (_name, task, input) => {
    const res = await runAiTask(task, input, ctx(me), makeDeps({ matches }));
    expect(res.meta.mock).toBe(true);
    expect(task.output.safeParse(res.data).success).toBe(true);
  });
});

describe("analyzeTimetable", () => {
  it("다른 사용자 경로나 .. 가 들어간 경로는 PERMISSION_DENIED", async () => {
    for (const path of ["timetableImages/uid-other/a.jpg", `timetableImages/${me}/../uid-other/a.jpg`, "a.jpg"]) {
      await expectReason(runAiTask(analyzeTimetableTask, { imagePaths: [path] }, ctx(me), makeDeps()), "PERMISSION_DENIED");
    }
  });

  it("이미지는 1~5장", async () => {
    await expectReason(runAiTask(analyzeTimetableTask, { imagePaths: [] }, ctx(me), makeDeps()), "INVALID_INPUT");
    const six = Array.from({ length: 6 }, (_, i) => `timetableImages/${me}/${i}.jpg`);
    await expectReason(runAiTask(analyzeTimetableTask, { imagePaths: six }, ctx(me), makeDeps()), "INVALID_INPUT");
  });

  it("시작 >= 종료나 없는 이미지 번호는 형식 오류", () => {
    const input = { imagePaths: [`timetableImages/${me}/a.jpg`] };
    const base = { name: "A", dayOfWeek: 1, building: null, room: null, confidence: 0.9 };
    const deps = makeDeps();
    expect(() =>
      analyzeTimetableTask.verify!({ courses: [{ ...base, startMinute: 600, endMinute: 600, sourceImageIndex: 0 }], warnings: [] }, input, deps),
    ).toThrow();
    expect(() =>
      analyzeTimetableTask.verify!({ courses: [{ ...base, startMinute: 600, endMinute: 660, sourceImageIndex: 1 }], warnings: [] }, input, deps),
    ).toThrow();
  });
});

describe("suggestChatReplies", () => {
  it("참여자가 아니거나 없는 매칭이면 PERMISSION_DENIED", async () => {
    await expectReason(runAiTask(suggestChatRepliesTask, { matchId: "match-1" }, ctx("intruder"), makeDeps({ matches })), "PERMISSION_DENIED");
    await expectReason(runAiTask(suggestChatRepliesTask, { matchId: "nope" }, ctx(me), makeDeps({ matches })), "PERMISSION_DENIED");
  });

  it("matchId 형식이 RTDB 키로 쓸 수 없으면 INVALID_INPUT", async () => {
    await expectReason(runAiTask(suggestChatRepliesTask, { matchId: "a/b" }, ctx(me), makeDeps({ matches })), "INVALID_INPUT");
  });

  it("답장이 3개가 아니거나 중복이면 형식 오류", async () => {
    const twoReplies = makeDeps({ mode: "live", matches, steps: [{ text: '{"replies":["a","b"]}' }, { text: '{"replies":["a","A ","b"]}' }] });
    const live = { ...suggestChatRepliesTask, buildRequest: async () => ({ contents: "test" }) };
    await expectReason(runAiTask(live, { matchId: "match-1" }, ctx(me), twoReplies), "AI_INVALID_OUTPUT");
  });
});

describe("recommendPlace", () => {
  const live = { ...recommendPlaceTask, buildRequest: async () => ({ contents: "test" }) };

  it("검증된 장소 목록이 비어 있는 live 모드에서는 어떤 장소도 통과하지 않는다", async () => {
    const out = '{"places":[{"placeId":"mock-bugak-library","name":"북악도서관","reason":"조용해요"}]}';
    const deps = makeDeps({ mode: "live", matches, steps: [{ text: out }, { text: out }] });
    await expectReason(runAiTask(live, { matchId: "match-1" }, ctx(me), deps), "AI_INVALID_OUTPUT");
  });

  it("목록 밖 id 는 버리고 이름은 서버 목록 값으로 바꾼다", () => {
    const deps = makeDeps({ mode: "mock" });
    const out = recommendPlaceTask.verify!(
      {
        places: [
          { placeId: "invented-place", name: "가짜 카페", reason: "x" },
          { placeId: "mock-bugak-library", name: "이름을 바꾼 도서관", reason: "조용해요" },
          { placeId: "mock-bugak-library", name: "중복", reason: "중복" },
        ],
      },
      { matchId: "match-1" },
      deps,
    );
    expect(out.places).toEqual([{ placeId: "mock-bugak-library", name: "북악도서관", reason: "조용해요" }]);
  });
});

describe("evaluateMatch (서버 내부)", () => {
  const input = validInputs[3][1] as Parameters<typeof evaluateMatch>[1];

  it("같은 입력이면 같은 점수", async () => {
    const a = await evaluateMatch(me, input, makeDeps());
    const b = await evaluateMatch(me, input, makeDeps());
    expect(a.data).toEqual(b.data);
    expect(a.data.score).toBeGreaterThanOrEqual(0);
    expect(a.data.score).toBeLessThanOrEqual(100);
  });

  it("필수 최소 겹침 60분 미만은 INVALID_INPUT", async () => {
    await expectReason(evaluateMatch(me, { ...input, overlapMinutes: 59 }, makeDeps()), "INVALID_INPUT");
  });

  it("live 프롬프트는 입력 JSON 만 보내고 결정적(temperature 0)이다", async () => {
    const ok = '{"score":82,"reasons":["공강이 길게 겹쳐요"],"suggestedActivity":"카페"}';
    const deps = makeDeps({ mode: "live", steps: [{ text: ok }] });
    const res = await evaluateMatch(me, input, deps);
    expect(res.data.score).toBe(82);
    const call = deps.client.calls[0];
    expect(call.temperature).toBe(0);
    expect(JSON.parse(call.contents as string)).toMatchObject({ overlapMinutes: input.overlapMinutes });
    expect(call.contents as string).not.toContain(me);
    expect(call.systemInstruction).toContain("건물 이름을 쓰지 않는다");
  });

  it("범위 밖 점수는 형식 오류", async () => {
    const live = { ...evaluateMatchTask, buildRequest: async () => ({ contents: "test" }) };
    const bad = '{"score":120,"reasons":["a"],"suggestedActivity":"카페"}';
    const deps = makeDeps({ mode: "live", steps: [{ text: bad }, { text: bad }] });
    await expectReason(runAiTask(live, input, ctx(me), deps), "AI_INVALID_OUTPUT");
  });
});

describe("generateProfile", () => {
  it("bio 는 150자를 넘을 수 없다", () => {
    expect(generateProfileTask.output.safeParse({ bio: "가".repeat(151), suggestedTags: [] }).success).toBe(false);
  });
});
