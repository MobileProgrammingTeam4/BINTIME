import { logger } from "firebase-functions";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createAiCallable } from "../src/ai/callable";
import { normalizeApiKey } from "../src/ai/deps";
import { describeError, redactSecrets } from "../src/ai/errors";
import { aiPingTask } from "../src/ai/tasks/aiPing";

const FAKE_KEY = "AQ.TestOnly_fake-key.123";

describe("normalizeApiKey", () => {
  it.each([
    ["끝 CRLF", `${FAKE_KEY}\r\n`],
    ["끝 LF", `${FAKE_KEY}\n`],
    ["BOM + 끝 CRLF", `﻿${FAKE_KEY}\r\n`],
    ["앞뒤 공백·탭", `  \t${FAKE_KEY} `],
  ])("%s 를 지운다", (_label, raw) => {
    expect(normalizeApiKey(raw)).toBe(FAKE_KEY);
  });

  it("비어 있으면 빈 문자열", () => {
    expect(normalizeApiKey(undefined)).toBe("");
    expect(normalizeApiKey(" \r\n")).toBe("");
  });

  it("가운데에 쓸 수 없는 문자가 있으면 값 없이 AI_UNAVAILABLE", () => {
    try {
      normalizeApiKey("AQ.abc def");
      throw new Error("should throw");
    } catch (error) {
      expect(error).toMatchObject({ reason: "AI_UNAVAILABLE", retryable: false });
      expect((error as Error).message).not.toContain("abc");
    }
  });
});

describe("오류 로그 정리", () => {
  afterEach(() => vi.restoreAllMocks());

  it("키처럼 보이는 문자열을 가린다", () => {
    expect(redactSecrets(`bad header ${FAKE_KEY}\r\n and AIzaSyFakeFake123`)).toBe("bad header [REDACTED]\r\n and [REDACTED]");
  });

  it("오류 이름·코드·메시지만 남긴다", () => {
    const error = Object.assign(new TypeError(`Invalid header value ${FAKE_KEY}`), { code: "ERR_INVALID_CHAR", headers: { key: FAKE_KEY } });
    const summary = describeError(error);
    expect(summary).toEqual({ errorName: "TypeError", errorCode: "ERR_INVALID_CHAR", errorMessage: "Invalid header value [REDACTED]" });
    expect(JSON.stringify(summary)).not.toContain("TestOnly");
  });

  it("Callable 에서 의존성 준비가 실패해도 원인을 로그에 남기고 앱에는 INTERNAL 만 보낸다", async () => {
    const errorLog = vi.spyOn(logger, "error").mockImplementation(() => undefined);
    const fn = createAiCallable(aiPingTask, {}, () => {
      throw new TypeError(`boom ${FAKE_KEY}`);
    });
    const request = { data: {}, auth: { uid: "uid-me", token: {} }, rawRequest: {} } as never;
    await expect(fn.run(request)).rejects.toMatchObject({ code: "internal", details: { reason: "INTERNAL" } });
    const [message, payload] = errorLog.mock.calls[0];
    expect(message).toBe("ai_callable_unexpected_error");
    expect(payload).toMatchObject({ task: "aiPing", errorName: "TypeError", errorMessage: "boom [REDACTED]" });
  });
});
