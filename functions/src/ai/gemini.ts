import { ApiError, GoogleGenAI, type ContentListUnion } from "@google/genai";
import { AiError } from "./errors";

/** 작업이 만드는 프롬프트. 개인정보는 필요한 최소값만 담는다. */
export interface ModelRequest {
  systemInstruction?: string;
  contents: ContentListUnion;
  temperature?: number;
  maxOutputTokens?: number;
}

export interface ModelCall extends ModelRequest {
  model: string;
  /** 응답 JSON 스키마(output zod 스키마에서 생성) */
  jsonSchema: unknown;
  /** 이번 시도에 쓸 시간. SDK 가 Google 쪽 마감으로도 보낸다. 게이트웨이가 남은 전체 시간으로 정한다. */
  timeoutMs: number;
}

export interface ModelUsage {
  promptTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
}

export interface ModelResult {
  text: string | undefined;
  usage?: ModelUsage;
  modelVersion?: string;
}

/** 테스트에서 가짜 구현으로 바꿀 수 있도록 Gemini SDK 를 감싼다. */
export interface ModelClient {
  generate(call: ModelCall): Promise<ModelResult>;
}

const BLOCKED_FINISH_REASONS = new Set(["SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION"]);

export class GenAiModelClient implements ModelClient {
  private readonly ai: GoogleGenAI;

  constructor(apiKey: string) {
    // 재시도는 게이트웨이가 제한 시간 안에서 직접 한다.
    this.ai = new GoogleGenAI({ apiKey, httpOptions: { retryOptions: { attempts: 1 } } });
  }

  async generate(call: ModelCall): Promise<ModelResult> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), call.timeoutMs);
    try {
      const response = await this.ai.models.generateContent({
        model: call.model,
        contents: call.contents,
        config: {
          systemInstruction: call.systemInstruction,
          temperature: call.temperature,
          maxOutputTokens: call.maxOutputTokens,
          responseMimeType: "application/json",
          responseJsonSchema: call.jsonSchema,
          abortSignal: controller.signal,
          httpOptions: { timeout: call.timeoutMs },
        },
      });
      const finishReason = response.candidates?.[0]?.finishReason;
      if (response.promptFeedback?.blockReason || (finishReason && BLOCKED_FINISH_REASONS.has(finishReason))) {
        throw new AiError("AI_BLOCKED", `blocked: ${response.promptFeedback?.blockReason ?? finishReason}`);
      }
      return {
        text: response.text,
        modelVersion: response.modelVersion,
        usage: {
          promptTokens: response.usageMetadata?.promptTokenCount,
          outputTokens: response.usageMetadata?.candidatesTokenCount,
          totalTokens: response.usageMetadata?.totalTokenCount,
        },
      };
    } catch (error) {
      throw classify(error, controller.signal.aborted);
    } finally {
      clearTimeout(timer);
    }
  }
}

/** Google 이 마감 초과로 돌려준 504(status DEADLINE_EXCEEDED)인지. 오류 본문의 상태 값만 본다. */
export function isDeadlineExceeded(error: ApiError): boolean {
  return error.status === 504 && /DEADLINE_EXCEEDED/.test(error.message ?? "");
}

/** SDK·네트워크 오류를 표준 사유로 바꾼다. 원문 메시지는 내부 로그용으로만 남긴다. */
export function classify(error: unknown, aborted: boolean): AiError {
  if (error instanceof AiError) return error;
  if (aborted || (error instanceof Error && (error.name === "AbortError" || error.name === "TimeoutError"))) {
    return new AiError("AI_TIMEOUT", "gemini timeout");
  }
  if (error instanceof ApiError) {
    const upstreamStatus = error.status;
    // SDK 는 우리 제한 시간을 Google 쪽 마감으로도 보낸다. 그 마감이 지나 돌아온 504 는 우리 시간 초과와 같으므로
    // 재시도하지 않는다(다시 보내면 최악 시간이 두 배가 된다, #48). 마감 표시가 없는 504 는 다른 5xx 처럼 재시도한다.
    if (isDeadlineExceeded(error)) return new AiError("AI_TIMEOUT", "gemini deadline exceeded", { upstreamStatus });
    if (upstreamStatus === 429) return new AiError("AI_RATE_LIMITED", "gemini 429", { transient: true, upstreamStatus });
    if (upstreamStatus >= 500) return new AiError("AI_UNAVAILABLE", `gemini ${upstreamStatus}`, { transient: true, upstreamStatus });
    // 400·401·402(크레딧 소진)·403·404: 잘못된 모델 ID·키·결제 상태. 다시 시도해도 같으므로
    // 게이트웨이도 재시도하지 않고 앱에도 retryable: false 로 알린다.
    return new AiError("AI_UNAVAILABLE", `gemini ${upstreamStatus}`, { retryable: false, upstreamStatus });
  }
  return new AiError("AI_UNAVAILABLE", error instanceof Error ? error.message : "unknown", { transient: true });
}
