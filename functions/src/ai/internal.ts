import { randomUUID } from "node:crypto";
import { defaultDeps } from "./deps";
import { runAiTask } from "./gateway";
import { evaluateMatchTask, type EvaluateMatchInput, type EvaluateMatchOutput } from "./tasks/evaluateMatch";
import type { AiDeps, AiResponse } from "./types";

/**
 * 서버 내부 전용 호출(A 의 매칭 검색 함수에서 사용). 앱에서 직접 부를 수 없다.
 * searcherUid 는 호출한 함수가 Auth 토큰에서 확인한 값을 넘긴다. 오류는 AiError 로 던지며,
 * 호출하는 쪽은 실패해도 검색을 멈추지 않고 점수 없이 결과를 돌려줘야 한다(BT-028 완료 조건).
 */
export function evaluateMatch(
  searcherUid: string,
  input: EvaluateMatchInput,
  deps: AiDeps = defaultDeps(),
): Promise<AiResponse<EvaluateMatchOutput>> {
  return runAiTask(evaluateMatchTask, input, { uid: searcherUid, requestId: randomUUID() }, deps);
}
