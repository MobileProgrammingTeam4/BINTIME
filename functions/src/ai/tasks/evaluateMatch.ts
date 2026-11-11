import { z } from "zod";
import type { AiTask } from "../types";
import { text } from "./shared";

/**
 * A(BT-027) 매칭률 점수·추천 이유. **Callable 로 열지 않는 서버 내부 작업**이다.
 * A 의 매칭 검색 함수가 규칙 기반 필수 조건(양측 ON, 겹침 >= minOverlap, 목적·차단·충돌)을 통과한 후보에 대해서만 호출한다.
 * 앱이 직접 부르면 필수 조건 검사를 우회할 수 있으므로 index.ts 에 export 하지 않는다.
 * 팀 결정으로 Callable 이 필요해지면 index.ts 에서 createAiCallable(evaluateMatchTask) 한 줄로 열 수 있다
 * (그때는 authorize 에 적격성 재검사를 반드시 넣는다).
 * 점수는 추천 정렬용 내부 지표이며 성공 확률이 아니다. 캐시는 BT-028(A).
 * live 프롬프트는 BT-027 에서 buildRequest 로 추가한다.
 */

/** 이름·UID·시간표 원본은 넣지 않는다. 겹침·목적·공개 프로필 요약만 넣는다. */
const side = z
  .object({
    department: text(1, 40),
    grade: z.number().int().min(1).max(6),
    tags: z.array(text(1, 20)).max(10).default([]),
    bio: z.string().trim().max(150).optional(),
    /** 공강 직전·직후 수업 건물 */
    nearbyBuildings: z.array(text(1, 40)).max(4).default([]),
  })
  .strict();

const input = z
  .object({
    /** 필수 조건 최소값 60분 이상만 들어온다(규칙 검사의 마지막 방어선). */
    overlapMinutes: z.number().int().min(60).max(1440),
    purposes: z.array(text(1, 20)).max(5).default([]),
    commonCourses: z.array(text(1, 60)).max(10).default([]),
    searcher: side,
    candidate: side,
  })
  .strict();

const output = z
  .object({
    score: z.number().int().min(0).max(100),
    reasons: z.array(text(1, 80)).min(1).max(3),
    suggestedActivity: text(1, 40),
  })
  .strict();

export type EvaluateMatchInput = z.input<typeof input>;
export type EvaluateMatchOutput = z.infer<typeof output>;

export const evaluateMatchTask: AiTask<z.infer<typeof input>, EvaluateMatchOutput> = {
  name: "evaluateMatch",
  input,
  output,
  timeoutMs: 10_000,
  /** 검색한 사용자 기준. 후보마다 1회라 다른 기능보다 크게 잡는다(BT-028 캐시로 줄인다). */
  dailyLimit: 300,
  // 같은 입력이면 같은 점수가 나오도록 결정적으로 만든다.
  mock: ({ overlapMinutes, commonCourses, searcher, candidate }) => {
    const sharedTags = searcher.tags.filter((tag) => candidate.tags.includes(tag));
    const score = Math.min(100, 50 + Math.min(overlapMinutes, 180) / 6 + sharedTags.length * 5 + commonCourses.length * 5);
    const reasons = [`공강이 ${overlapMinutes}분 겹쳐요`];
    if (sharedTags.length > 0) reasons.push(`관심사가 비슷해요 (${sharedTags.slice(0, 2).join(", ")})`);
    if (commonCourses.length > 0) reasons.push(`'${commonCourses[0]}' 수업을 함께 들어요`);
    return { score: Math.round(score), reasons, suggestedActivity: sharedTags[0]?.replace(/^#/, "") || "카페" };
  },
};
