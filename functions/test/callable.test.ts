import { HttpsError } from "firebase-functions/v2/https";
import { afterEach, describe, expect, it, vi } from "vitest";
import { logger } from "firebase-functions";
import { createAiCallable } from "../src/ai/callable";
import { AiError, REASONS, toHttpsError } from "../src/ai/errors";
import { aiPingTask } from "../src/ai/tasks/aiPing";
import { makeDeps } from "./helpers";

type Token = { email?: unknown; email_verified?: unknown };
const SCHOOL: Token = { email: "student@kookmin.ac.kr", email_verified: true };

const request = (auth?: { uid: string; token?: Token }) =>
  ({ data: {}, auth: auth ? { uid: auth.uid, token: auth.token ?? SCHOOL } : undefined, rawRequest: {} }) as never;

afterEach(() => vi.restoreAllMocks());

describe("Callable 래퍼", () => {
  it("Auth 토큰이 없으면 unauthenticated + 표준 details", async () => {
    const fn = createAiCallable(aiPingTask, {}, () => makeDeps());
    await expect(fn.run(request())).rejects.toMatchObject({
      code: "unauthenticated",
      details: { reason: "UNAUTHENTICATED", retryable: false, requestId: expect.any(String) },
    });
  });

  it("Auth 토큰의 uid 로 실행한다", async () => {
    const deps = makeDeps();
    const fn = createAiCallable(aiPingTask, {}, () => deps);
    const res = await fn.run(request({ uid: "uid-me" }));
    expect(res.data).toEqual({ reply: "pong" });
    expect([...deps.usageStore.counts.keys()][0]).toMatch(/^uid-me_/);
  });
});

describe("Callable 래퍼: 학교 이메일 검사", () => {
  const denied = {
    code: "permission-denied",
    details: { reason: "PERMISSION_DENIED", retryable: false, requestId: expect.any(String) },
  };

  it.each<[string, Token]>([
    ["이메일 미인증", { email: "student@kookmin.ac.kr", email_verified: false }],
    ["email_verified 없음", { email: "student@kookmin.ac.kr" }],
    ["이메일 없음", { email_verified: true }],
    ["허용 밖 도메인", { email: "student@gmail.com", email_verified: true }],
    ["접미사 속임수", { email: "student@kookmin.ac.kr.evil.com", email_verified: true }],
  ])("%s → PERMISSION_DENIED, 사용량 차감 없음", async (_label, token) => {
    const deps = makeDeps();
    const fn = createAiCallable(aiPingTask, {}, () => deps);
    await expect(fn.run(request({ uid: "uid-me", token }))).rejects.toMatchObject(denied);
    expect(deps.usageStore.counts.size).toBe(0);
  });

  it("허용 목록 설정이 비면 모두 거부", async () => {
    const deps = makeDeps({ allowedEmailSuffixes: [] });
    const fn = createAiCallable(aiPingTask, {}, () => deps);
    await expect(fn.run(request({ uid: "uid-me" }))).rejects.toMatchObject(denied);
    expect(deps.usageStore.counts.size).toBe(0);
  });

  it("대소문자가 달라도 학교 이메일이면 통과", async () => {
    const deps = makeDeps();
    const fn = createAiCallable(aiPingTask, {}, () => deps);
    const res = await fn.run(request({ uid: "uid-me", token: { email: "Student@Kookmin.AC.KR", email_verified: true } }));
    expect(res.data).toEqual({ reply: "pong" });
  });

  it("거절 로그에 이메일 원문을 남기지 않는다", async () => {
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);
    const fn = createAiCallable(aiPingTask, {}, () => makeDeps());
    const email = "secret.person@kookmin.ac.kr.evil.com";
    await expect(fn.run(request({ uid: "uid-me", token: { email, email_verified: true } }))).rejects.toMatchObject(denied);
    expect(warn).toHaveBeenCalledWith("ai_callable_denied", expect.objectContaining({ cause: "EMAIL_DOMAIN_NOT_ALLOWED" }));
    const logged = JSON.stringify(warn.mock.calls);
    expect(logged).not.toContain("secret.person");
    expect(logged).not.toContain("evil.com");
  });
});

describe("toHttpsError", () => {
  it("모든 사유가 HttpsError 코드로 바뀐다", () => {
    for (const reason of Object.keys(REASONS) as Array<keyof typeof REASONS>) {
      const error = toHttpsError(new AiError(reason, "secret internal detail"), "r1");
      expect(error).toBeInstanceOf(HttpsError);
      expect(error.code).toBe(REASONS[reason].code);
      expect(error.message).not.toContain("secret internal detail");
      expect(error.details).toMatchObject({ reason, retryable: REASONS[reason].retryable, requestId: "r1" });
    }
  });

  it("예상하지 못한 오류는 원문을 숨기고 INTERNAL", () => {
    const error = toHttpsError(new Error("stack trace with key AIza..."), "r2");
    expect(error.code).toBe("internal");
    expect(error.message).not.toContain("AIza");
    expect(error.details).toMatchObject({ reason: "INTERNAL" });
  });
});
