import { logger as firebaseLogger } from "firebase-functions";
import { onValueCreated } from "firebase-functions/v2/database";
import { fanOutMessage, type FanoutResult, type RootRef } from "./fanout";

/**
 * RTDB 인스턴스(bintime-mobile-default-rtdb)가 asia-southeast1 이므로 트리거도 같은 지역에 둔다
 * (Firebase 권장: RTDB 트리거는 인스턴스와 같은 지역). 앱이 호출하는 Callable(asia-northeast3)과는 무관하다.
 * instance 를 지정하지 않으면 그 지역의 모든 인스턴스("*")에 걸린다. 지금은 기본 인스턴스 하나뿐이다.
 */
export const CHAT_TRIGGER_REGION = "asia-southeast1";

/**
 * 실패한 이벤트 재처리 정책(PR #96 재검토 P2). https://firebase.google.com/docs/functions/retries
 * - retry: true — 처리하지 못한 이벤트를 플랫폼이 다시 보낸다(2세대: 10~600초 지수 백오프, 최대 24시간).
 *   fanOutMessage 는 멤버별로 멱등(recentMessageIds)이라 다시 처리해도 이미 반영된 쪽은 다시 세지 않는다.
 * - 종료 조건: 이벤트 발생 후 MAX_EVENT_AGE_MS 가 지났으면 처리하지 않고 정상 종료해 재시도를 멈춘다.
 *   예상하지 못한 영구 오류(코드 버그 등)도 이 시간 안에서만 반복된다(실패 이벤트당 대략 10회 이내).
 * - 영구 결과(형식 오류·비멤버 발신자)는 예외 없이 끝나 재시도하지 않는다. 예외는 RTDB 읽기·트랜잭션 실패 같은 일시 오류만 던진다.
 */
export const CHAT_TRIGGER_RETRY = true;
export const MAX_EVENT_AGE_MS = 60 * 60 * 1000;

/**
 * Functions 에뮬레이터는 RTDB 트리거를 us-central1 일 때만 실행한다(firebase-tools 고정값, 아니면 "will not trigger" 경고).
 * 로컬 검증용으로 에뮬레이터 안에서만 us-central1 을 쓰고, 배포는 항상 asia-southeast1 이다.
 */
export function chatTriggerRegion(env: NodeJS.ProcessEnv = process.env): string {
  return env.FUNCTIONS_EMULATOR === "true" ? "us-central1" : CHAT_TRIGGER_REGION;
}

/** 트리거가 처리에 쓰는 이벤트 정보(테스트에서 직접 만든다). */
export interface MessageEvent {
  /** 이벤트 발생 시각(RFC 3339). 재전달돼도 처음 값이 유지된다. */
  time: string;
  matchId: string;
  messageId: string;
  root: RootRef;
  value: unknown;
}

export type MessageEventOutcome = FanoutResult | "expired";

interface Logger {
  warn(message: string, data?: object): void;
  error(message: string, data?: object): void;
}

type FanOut = (root: RootRef, matchId: string, messageId: string, value: unknown) => Promise<FanoutResult>;

/**
 * 이벤트 하나 처리. 일시 오류는 예외를 그대로 던져 플랫폼이 재시도하게 하고,
 * 너무 오래된 이벤트·영구 결과는 정상 종료한다.
 */
export async function handleMessageEvent(event: MessageEvent, now: number = Date.now(),
  fanOut: FanOut = fanOutMessage, logger: Logger = firebaseLogger): Promise<MessageEventOutcome> {
  const { matchId, messageId } = event;
  const eventTime = Date.parse(event.time);
  const ageMs = now - eventTime;
  if (!Number.isFinite(eventTime) || ageMs > MAX_EVENT_AGE_MS) {
    // 재시도를 멈춘다. 이 메시지의 목록 갱신(마지막 메시지·상대 안읽음)은 빠진다. 메시지 자체는 저장돼 있다.
    logger.error("chat fan-out dropped: event too old", { matchId, messageId, eventTime: event.time, ageMs });
    return "expired";
  }
  try {
    const result = await fanOut(event.root, matchId, messageId, event.value);
    if (result !== "applied") logger.warn("chat fan-out skipped", { matchId, messageId, result });
    return result;
  } catch (error) {
    logger.warn("chat fan-out failed, will retry", { matchId, messageId, ageMs, error: String(error) });
    throw error;
  }
}

export function createMessageTrigger() {
  return onValueCreated({
    ref: "/chatRooms/{matchId}/messages/{messageId}",
    region: chatTriggerRegion(),
    retry: CHAT_TRIGGER_RETRY,
    timeoutSeconds: 30,
    maxInstances: 10,
  }, async (event) => {
    await handleMessageEvent({
      time: event.time,
      matchId: event.params.matchId,
      messageId: event.params.messageId,
      // 이벤트가 온 인스턴스의 루트(에뮬레이터 포함)를 그대로 쓴다.
      root: event.data.ref.root as unknown as RootRef,
      value: event.data.val(),
    });
  });
}
