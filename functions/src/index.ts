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
import { cancelRequestCallable, createRequestCallable, respondRequestCallable } from "./requests/callable";
import { createSearchCallable } from "./matching/callable";
import { createMessageTrigger } from "./chat/trigger";
import { createRequestMaintenanceSchedule } from "./requests/scheduled";
import { createChatLinkCallable, createChatLinkTrigger } from "./chat/linkCallable";
export { notificationOnRequestCreated, notificationOnMatchChanged, notificationOnReviewCreated,
  notificationOnMessageCreated, notificationOnPushQueued, notificationOnAvailabilityChanged,
  notificationOnTimetableChanged, notificationOnSettingsChanged, notificationOnUserChanged, maintainNotifications,
  registerNotificationDevice } from "./notification/functions";

/** D · BT-019 요청 생성. */
export const createMatchRequest = createRequestCallable();
/** D · BT-020. 발신자 본인의 PENDING 요청만 취소한다. */
export const cancelMatchRequest = cancelRequestCallable();
/** D · BT-031. 수신자만 수락·거절, 수락과 확정 매칭은 한 트랜잭션. */
export const respondToMatchRequest = respondRequestCallable();
/** D · BT-032. 배포 시 예약 작업이 생성되므로 운영 적용은 별도 승인 후 진행한다. */
export const maintainMatchRequests = createRequestMaintenanceSchedule();
/** D · BT-033. Firestore 매칭 → RTDB 방 연결과 참여자의 멱등 재시도. */
export const chatOnMatchCreated = createChatLinkTrigger();
export const ensureMatchChat = createChatLinkCallable();

/** BT-024 연결 확인 */
export const aiPing = createAiCallable(aiPingTask);
/** C · BT-026 */
// 요청 하나가 이미지 원본(최대 14MiB)·base64(약 19.6MB)·요청 본문으로 대략 50~70MB를 쓴다.
// 기본 동시 실행 80이면 512MiB 인스턴스가 메모리 부족이 될 수 있어 4로 제한한다(4 × 70MB + 기본 사용량 < 512MiB).
// 최대 인스턴스 10 × 4 = 동시 40건. 메모리 사용량이 높게 나오면 그때 1GiB를 검토한다(#112 리뷰 C).
export const analyzeTimetable = createAiCallable(analyzeTimetableTask, { timeoutSeconds: 120, memory: "512MiB", concurrency: 4 });
/** B · BT-029 */
export const generateProfile = createAiCallable(generateProfileTask);
/** E · BT-042 */
export const recommendPlace = createAiCallable(recommendPlaceTask);
/** E · BT-041 */
export const suggestChatReplies = createAiCallable(suggestChatRepliesTask);

/** A · BT-017 매칭 후보 검색 */
export const searchMatches = createSearchCallable();

/** E · BT-022 새 메시지 → 두 멤버 userChatRooms 의 마지막 메시지·상대 안읽음 수 갱신(RTDB 트리거, asia-southeast1). */
export const chatOnMessageCreated = createMessageTrigger();

// A · BT-027 evaluateMatch 는 서버 내부 전용이라 Callable 로 열지 않는다(./ai/internal 의 evaluateMatch 사용).
// 팀 결정으로 열어야 하면: export const evaluateMatch = createAiCallable(evaluateMatchTask);
