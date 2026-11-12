/**
 * BIN TIME Cloud Functions 진입점.
 * BT-024: Gemini 공통 게이트웨이. 앱은 아래 Callable 만 호출하고 Gemini 를 직접 부르지 않는다.
 * 리전: asia-northeast3 (앱은 FirebaseFunctions.getInstance("asia-northeast3") 사용)
 */
import { createAiCallable } from "./ai/callable";
import { aiPingTask } from "./ai/tasks/aiPing";
import { analyzeTimetableTask } from "./ai/tasks/analyzeTimetable";
import { generateProfileTask } from "./ai/tasks/generateProfile";
import { recommendPlaceTask } from "./ai/tasks/recommendPlace";
import { suggestChatRepliesTask } from "./ai/tasks/suggestChatReplies";
import { createSearchCallable } from "./matching/callable";

/** BT-024 연결 확인 */
export const aiPing = createAiCallable(aiPingTask);
/** C · BT-026 */
export const analyzeTimetable = createAiCallable(analyzeTimetableTask, { timeoutSeconds: 120, memory: "512MiB" });
/** B · BT-029 */
export const generateProfile = createAiCallable(generateProfileTask);
/** E · BT-042 */
export const recommendPlace = createAiCallable(recommendPlaceTask);
/** E · BT-041 */
export const suggestChatReplies = createAiCallable(suggestChatRepliesTask);

/** A · BT-017 매칭 후보 검색 */
export const searchMatches = createSearchCallable();

// A · BT-027 evaluateMatch 는 서버 내부 전용이라 Callable 로 열지 않는다(./ai/internal 의 evaluateMatch 사용).
// 팀 결정으로 열어야 하면: export const evaluateMatch = createAiCallable(evaluateMatchTask);
