import { describe, expect, it } from "vitest";
import { createGetMatchReviewCallable, createSubmitReviewCallable } from "../src/reviews/callable";
import { getInput, REVIEW_KEYWORDS, reviewId, submitInput } from "../src/reviews/review";

/** BT-044 후기 입력 계약과 Callable 앞단 검사(저장소 접근 전). */
const matchId = "a".repeat(64);
const valid = { matchId, satisfaction: "GOOD", keywords: ["ON_TIME", "KIND_MANNER"], comment: " 좋았어요 " };
const school = { uid: "me", token: { email: "me@school.ac.kr", email_verified: true } };
const noDb = () => { throw new Error("must not access database"); };

describe("submitReview 입력", () => {
  it("만족도는 필수, 키워드·한 줄 후기는 선택. 한 줄 후기는 앞뒤 공백을 지우고 비면 null", () => {
    expect(submitInput.parse(valid)).toMatchObject({ comment: "좋았어요" });
    expect(submitInput.parse({ matchId, satisfaction: "GREAT", keywords: [] }).comment).toBeNull();
    expect(submitInput.parse({ ...valid, comment: "   " }).comment).toBeNull();
    expect(submitInput.parse({ ...valid, comment: null }).comment).toBeNull();
    expect(submitInput.safeParse({ matchId, keywords: [] }).success).toBe(false);
  });

  it("한 줄 후기는 공백을 뺀 100자까지, 연락처는 지우지 않는다", () => {
    expect(submitInput.safeParse({ ...valid, comment: "가".repeat(100) }).success).toBe(true);
    expect(submitInput.safeParse({ ...valid, comment: ` ${"가".repeat(100)} ` }).success).toBe(true);
    expect(submitInput.safeParse({ ...valid, comment: "가".repeat(101) }).success).toBe(false);
    expect(submitInput.parse({ ...valid, comment: "010-1234-5678 연락 주세요" }).comment).toBe("010-1234-5678 연락 주세요");
  });

  it("정해진 만족도·키워드만, 키워드 중복 금지", () => {
    expect(submitInput.safeParse({ ...valid, satisfaction: "OK" }).success).toBe(false);
    expect(submitInput.safeParse({ ...valid, keywords: ["UNKNOWN"] }).success).toBe(false);
    expect(submitInput.safeParse({ ...valid, keywords: ["ON_TIME", "ON_TIME"] }).success).toBe(false);
    expect(submitInput.safeParse({ ...valid, keywords: [...REVIEW_KEYWORDS] }).success).toBe(true);
  });

  it("UID·상대 UID·자격 같은 위조 필드와 잘못된 matchId 는 거부한다", () => {
    for (const patch of [{ authorUid: "victim" }, { targetUid: "other" }, { reviewEligible: true }]) {
      expect(submitInput.safeParse({ ...valid, ...patch }).success).toBe(false);
    }
    expect(submitInput.safeParse({ ...valid, matchId: "../x" }).success).toBe(false);
    expect(getInput.safeParse({ matchId }).success).toBe(true);
    expect(getInput.safeParse({ matchId, uid: "victim" }).success).toBe(false);
  });

  it("문서 ID 는 매칭·작성자당 하나", () => {
    expect(reviewId(matchId, "me")).toBe(`${matchId}_me`);
  });
});

describe("후기 Callable 앞단", () => {
  it("미로그인·학교 이메일 아님·잘못된 입력은 저장소 접근 전에 거부한다", async () => {
    for (const callable of [createSubmitReviewCallable(noDb, () => [".ac.kr"]), createGetMatchReviewCallable(noDb, () => [".ac.kr"])]) {
      await expect(callable.run({ data: { matchId } } as never)).rejects.toMatchObject({ code: "unauthenticated" });
      await expect(callable.run({ auth: { uid: "me", token: { email: "me@gmail.com", email_verified: true } }, data: { matchId } } as never))
        .rejects.toMatchObject({ code: "permission-denied" });
      await expect(callable.run({ auth: school, data: { matchId: "bad" } } as never))
        .rejects.toMatchObject({ code: "invalid-argument", details: { reason: "INVALID_INPUT", requestId: expect.any(String) } });
    }
  });

  it("예상하지 못한 오류는 unavailable/RETRY_LATER 로 바꾼다", async () => {
    const broken = { submit: async () => { throw new Error("db down"); } } as never;
    await expect(createSubmitReviewCallable(() => broken, () => [".ac.kr"]).run({ auth: school, data: valid } as never))
      .rejects.toMatchObject({ code: "unavailable", details: { reason: "RETRY_LATER" } });
  });
});
