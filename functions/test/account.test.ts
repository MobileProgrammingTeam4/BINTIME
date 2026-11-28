import { describe, expect, it } from "vitest";
import { createDeleteAccountCallable } from "../src/account/callable";
import type { AccountDeletionService } from "../src/account/deleteAccount";

const schoolAuth = (uid: string) => ({ uid, token: { email: `${uid}@school.ac.kr`, email_verified: true } });
const suffixes = () => [".ac.kr"];
const noDatabase = () => { throw new Error("must not access database"); };

describe("BT-045 deleteAccount Callable", () => {
  it("미로그인·학교 이메일 아님·입력 있음은 저장소에 접근하기 전에 거절한다", async () => {
    const callable = createDeleteAccountCallable(noDatabase, suffixes);
    await expect(callable.run({ data: null } as never)).rejects.toMatchObject({ code: "unauthenticated" });
    await expect(callable.run({ auth: { uid: "me", token: { email: "me@gmail.com", email_verified: true } }, data: null } as never))
      .rejects.toMatchObject({ code: "permission-denied" });
    // 다른 사람 UID 를 본문으로 넘겨 탈퇴시키는 시도는 입력 단계에서 막는다.
    await expect(callable.run({ auth: schoolAuth("me"), data: { uid: "victim" } } as never))
      .rejects.toMatchObject({ code: "invalid-argument", details: { reason: "INVALID_INPUT" } });
  });

  it("토큰 UID 로만 탈퇴하고, 실패는 다시 시도할 수 있는 오류로 알린다", async () => {
    const called: string[] = [];
    const ok = createDeleteAccountCallable(() => ({ delete: async (uid: string) => { called.push(uid); return { deleted: true } as never; } }) as unknown as AccountDeletionService, suffixes);
    await expect(ok.run({ auth: schoolAuth("me"), data: {} } as never)).resolves.toMatchObject({ deleted: true });
    await expect(ok.run({ auth: schoolAuth("me"), data: null } as never)).resolves.toMatchObject({ deleted: true });
    expect(called).toEqual(["me", "me"]);

    const failing = createDeleteAccountCallable(() => ({ delete: async () => { throw new Error("users/me 경로 오류"); } }) as unknown as AccountDeletionService, suffixes);
    await expect(failing.run({ auth: schoolAuth("me"), data: null } as never))
      .rejects.toMatchObject({ code: "unavailable", details: { reason: "INTERNAL", retryable: true } });
  });
});
