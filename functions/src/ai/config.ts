import type { AiMode } from "./types";

/** 앱의 모든 Callable 호출은 같은 리전을 지정해야 한다(FirebaseFunctions.getInstance("asia-northeast3")). */
export const REGION = "asia-northeast3";

export const SEOUL_TIME_ZONE = "Asia/Seoul";

/** live 모드에서 일시적 오류(429·5xx·형식 오류)일 때 최대 시도 횟수(첫 시도 포함) */
export const MAX_ATTEMPTS = 2;
export const RETRY_DELAY_MS = 500;
/**
 * 재시도 조건(BT-048, #48): 대기(RETRY_DELAY_MS) 뒤 남은 시간이 작업 제한 시간(timeoutMs)의 이 비율 이상일 때만 다시 시도한다.
 * timeoutMs 는 재시도까지 포함한 전체 시간이라, 남은 시간이 적으면 다시 보내도 시간 초과로 끝나기 쉽다.
 */
export const MIN_RETRY_BUDGET_RATIO = 0.5;

/**
 * AI_MODE 가 명시되면 그 값을 쓴다. 없으면 에뮬레이터는 mock, 배포 환경은 live.
 * 배포 환경이 실수로 mock 이 되지 않도록 기본값을 환경으로 나눈다.
 */
export function resolveMode(env: NodeJS.ProcessEnv = process.env): AiMode {
  const explicit = env.AI_MODE?.trim().toLowerCase();
  if (explicit === "mock" || explicit === "live") return explicit;
  return env.FUNCTIONS_EMULATOR === "true" ? "mock" : "live";
}

/** Asia/Seoul 기준 YYYY-MM-DD. 호출량은 이 날짜 단위로 센다. */
export function seoulDateKey(date: Date): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: SEOUL_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
}
