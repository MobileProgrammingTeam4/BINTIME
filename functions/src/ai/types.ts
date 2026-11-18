import type { z } from "zod";
import type { ModelClient, ModelRequest } from "./gemini";
import type { UsageStore } from "./usage";
import type { MatchAccess } from "./access";

export type AiMode = "mock" | "live";

export type TaskName =
  | "aiPing"
  | "analyzeTimetable"
  | "generateProfile"
  | "evaluateMatch"
  | "recommendPlace"
  | "suggestChatReplies";

/** 호출자 정보. uid 는 Callable 이면 Auth 토큰, 내부 호출이면 서버가 넣는다(요청 본문의 UID 는 쓰지 않는다). */
export interface AiContext {
  uid: string;
  requestId: string;
}

export interface AiDeps {
  mode: AiMode;
  /** live 모드에서 쓸 모델 ID. 비어 있으면 AI_UNAVAILABLE */
  model: string;
  /** live 모드에서만 호출한다. 키가 없으면 AI_UNAVAILABLE 을 던진다. */
  modelClient: () => ModelClient;
  usage: UsageStore;
  matches: MatchAccess;
  now: () => Date;
  sleep: (ms: number) => Promise<void>;
  /** 학교 이메일 허용 접미사(ALLOWED_EMAIL_SUFFIXES). 비어 있으면 Callable 호출을 모두 거부한다. */
  allowedEmailSuffixes: readonly string[];
  /**
   * 기본 버킷의 파일을 읽는다(analyzeTimetable). 크기가 maxBytes 를 넘으면 내려받지 않고 bytes 없이 돌려준다.
   * Storage 오류는 바꾸지 않고 그대로 던진다(작업이 AiError 로 바꾼다).
   */
  readImage: (path: string, maxBytes: number) => Promise<StoredImage>;
}

/** Storage 파일 메타데이터와 내용. bytes 는 크기 상한 이내일 때만 있다. */
export interface StoredImage {
  contentType: string | undefined;
  size: number;
  bytes?: Buffer;
}

/**
 * 용도별 AI 작업 정의. 공통 처리(인증·검증·호출량·호출·형식 검증)는 gateway.runAiTask 가 맡고,
 * 각 작업은 계약(스키마)·권한 검사·Mock 응답·프롬프트만 정의한다.
 */
export interface AiTask<I, O> {
  name: TaskName;
  input: z.ZodType<I>;
  output: z.ZodType<O>;
  /** Gemini 호출 1회의 제한 시간 */
  timeoutMs: number;
  /** 사용자별 하루 최대 호출 수(Asia/Seoul 날짜 기준) */
  dailyLimit: number;
  /** 같은 사용자의 같은 작업 연속 호출 최소 간격(ms). 없으면 간격 제한 없음. 위반 시 AI_RATE_LIMITED */
  minIntervalMs?: number;
  /** 소유권·참여자 검사. 실패하면 AiError("PERMISSION_DENIED") 를 던진다. */
  authorize?: (input: I, ctx: AiContext, deps: AiDeps) => Promise<void>;
  /** 스키마로 표현하기 어려운 규칙(중복, 허용 목록 등). 위반하면 AiError("AI_INVALID_OUTPUT") 를 던진다. */
  verify?: (output: O, input: I, deps: AiDeps) => O;
  /** 키 없이 계약대로 응답하는 Mock. 결과도 output 스키마·verify 를 통과해야 한다. */
  mock: (input: I, ctx: AiContext) => O;
  /** live 프롬프트. 없으면 AI_NOT_IMPLEMENTED(담당 이슈에서 추가). */
  buildRequest?: (input: I, ctx: AiContext, deps: AiDeps) => Promise<ModelRequest>;
}

export interface AiResponse<O> {
  task: TaskName;
  data: O;
  meta: {
    model: string;
    requestId: string;
    latencyMs: number;
    mock: boolean;
  };
}
