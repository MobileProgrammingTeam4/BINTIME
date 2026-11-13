import { logger } from "firebase-functions";
import { onValueCreated } from "firebase-functions/v2/database";
import { fanOutMessage, type RootRef } from "./fanout";

/**
 * RTDB 인스턴스(bintime-mobile-default-rtdb)가 asia-southeast1 이므로 트리거도 같은 지역에 둔다
 * (Firebase 권장: RTDB 트리거는 인스턴스와 같은 지역). 앱이 호출하는 Callable(asia-northeast3)과는 무관하다.
 * instance 를 지정하지 않으면 그 지역의 모든 인스턴스("*")에 걸린다. 지금은 기본 인스턴스 하나뿐이다.
 */
export const CHAT_TRIGGER_REGION = "asia-southeast1";

/**
 * Functions 에뮬레이터는 RTDB 트리거를 us-central1 일 때만 실행한다(firebase-tools 고정값, 아니면 "will not trigger" 경고).
 * 로컬 검증용으로 에뮬레이터 안에서만 us-central1 을 쓰고, 배포는 항상 asia-southeast1 이다.
 */
export function chatTriggerRegion(env: NodeJS.ProcessEnv = process.env): string {
  return env.FUNCTIONS_EMULATOR === "true" ? "us-central1" : CHAT_TRIGGER_REGION;
}

export function createMessageTrigger() {
  return onValueCreated({
    ref: "/chatRooms/{matchId}/messages/{messageId}",
    region: chatTriggerRegion(),
    timeoutSeconds: 30,
    maxInstances: 10,
  }, async (event) => {
    const { matchId, messageId } = event.params;
    // 이벤트가 온 인스턴스의 루트(에뮬레이터 포함)를 그대로 쓴다.
    const root = event.data.ref.root as unknown as RootRef;
    const result = await fanOutMessage(root, matchId, messageId, event.data.val());
    if (result !== "applied") logger.warn("chat fan-out skipped", { matchId, messageId, result });
  });
}
