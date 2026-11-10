import { HttpsError, type FunctionsErrorCode } from "firebase-functions/v2/https";

/** 앱이 FirebaseFunctionsException.getDetails() 의 reason 으로 분기하는 표준 오류 사유. */
export type AiErrorReason =
  | "UNAUTHENTICATED"
  | "PERMISSION_DENIED"
  | "INVALID_INPUT"
  | "QUOTA_EXCEEDED"
  | "AI_RATE_LIMITED"
  | "AI_TIMEOUT"
  | "AI_INVALID_OUTPUT"
  | "AI_BLOCKED"
  | "AI_UNAVAILABLE"
  | "AI_NOT_IMPLEMENTED"
  | "INTERNAL";

interface ReasonSpec {
  code: FunctionsErrorCode;
  /** 앱이 같은 요청을 다시 시도해도 되는지 */
  retryable: boolean;
  message: string;
}

export const REASONS: Record<AiErrorReason, ReasonSpec> = {
  UNAUTHENTICATED: { code: "unauthenticated", retryable: false, message: "로그인이 필요합니다." },
  PERMISSION_DENIED: { code: "permission-denied", retryable: false, message: "이 요청을 할 권한이 없습니다." },
  INVALID_INPUT: { code: "invalid-argument", retryable: false, message: "요청 형식이 올바르지 않습니다." },
  QUOTA_EXCEEDED: { code: "resource-exhausted", retryable: false, message: "오늘 사용할 수 있는 AI 횟수를 모두 사용했습니다." },
  AI_RATE_LIMITED: { code: "resource-exhausted", retryable: true, message: "AI 요청이 많습니다. 잠시 후 다시 시도해 주세요." },
  AI_TIMEOUT: { code: "deadline-exceeded", retryable: true, message: "AI 응답 시간이 초과되었습니다." },
  AI_INVALID_OUTPUT: { code: "internal", retryable: true, message: "AI 응답 형식이 올바르지 않습니다." },
  AI_BLOCKED: { code: "failed-precondition", retryable: false, message: "AI가 이 요청에 응답할 수 없습니다." },
  AI_UNAVAILABLE: { code: "unavailable", retryable: true, message: "AI 기능을 일시적으로 사용할 수 없습니다." },
  AI_NOT_IMPLEMENTED: { code: "unimplemented", retryable: false, message: "아직 준비되지 않은 AI 기능입니다." },
  INTERNAL: { code: "internal", retryable: false, message: "알 수 없는 오류가 발생했습니다." },
};

export interface AiErrorOptions {
  /** 게이트웨이가 같은 호출을 한 번 더 시도할 일시적 오류인지 */
  transient?: boolean;
  /** 입력 검증 실패 위치(필드 경로만, 값은 담지 않는다) */
  fields?: string[];
  /** 사유의 기본값 대신 앱에 알릴 retryable. 예: 402·403 은 다시 시도해도 실패하므로 false */
  retryable?: boolean;
  /** Gemini 가 돌려준 HTTP 상태 코드. 로그에만 남기고 앱에는 보내지 않는다. */
  upstreamStatus?: number;
}

export class AiError extends Error {
  readonly reason: AiErrorReason;
  /** 게이트웨이가 같은 호출을 한 번 더 시도할 일시적 오류인지(429·5xx·형식 오류). 앱에는 노출하지 않는다. */
  readonly transient: boolean;
  readonly fields?: string[];
  readonly retryable: boolean;
  readonly upstreamStatus?: number;

  constructor(reason: AiErrorReason, internalDetail?: string, options: AiErrorOptions = {}) {
    super(internalDetail ?? reason);
    this.name = "AiError";
    this.reason = reason;
    this.transient = options.transient ?? false;
    this.fields = options.fields;
    this.retryable = options.retryable ?? REASONS[reason].retryable;
    this.upstreamStatus = options.upstreamStatus;
  }
}

/** 로그에 넣기 전에 API 키처럼 보이는 문자열을 가린다(AQ.… / AIza…). */
export function redactSecrets(text: string): string {
  return text.replace(/AQ\.[A-Za-z0-9._-]+/g, "[REDACTED]").replace(/AIza[A-Za-z0-9_-]+/g, "[REDACTED]");
}

/** 예상하지 못한 오류를 로그용으로 요약한다. 오류 객체 전체(요청 헤더 등)는 남기지 않는다. */
export function describeError(error: unknown): { errorName: string; errorCode?: string; errorMessage: string } {
  if (!(error instanceof Error)) return { errorName: typeof error, errorMessage: redactSecrets(String(error)).slice(0, 300) };
  const code = (error as { code?: unknown }).code;
  return {
    errorName: error.name,
    errorCode: typeof code === "string" || typeof code === "number" ? String(code) : undefined,
    errorMessage: redactSecrets(error.message).slice(0, 300),
  };
}

export interface AiErrorDetails {
  reason: AiErrorReason;
  retryable: boolean;
  requestId: string;
  fields?: string[];
}

/** 내부 오류 메시지·스택은 앱에 보내지 않고, 표준 메시지와 details 만 보낸다. */
export function toHttpsError(error: unknown, requestId: string): HttpsError {
  const aiError = error instanceof AiError ? error : new AiError("INTERNAL");
  const spec = REASONS[aiError.reason];
  const details: AiErrorDetails = { reason: aiError.reason, retryable: aiError.retryable, requestId };
  if (aiError.fields && aiError.fields.length > 0) details.fields = aiError.fields;
  return new HttpsError(spec.code, spec.message, details);
}
