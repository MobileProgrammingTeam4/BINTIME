import { describe, expect, it } from "vitest";
import * as functions from "../src/index";

/** 배포 설정(__endpoint). firebase-functions 가 배포 때 읽는 값과 같다. */
interface Endpoint {
  concurrency: unknown;
  availableMemoryMb: number | null | undefined;
  timeoutSeconds: number | null | undefined;
  maxInstances: number | null | undefined;
  region: string[];
}

const endpoint = (name: keyof typeof functions): Endpoint =>
  (functions[name] as unknown as { __endpoint: Endpoint }).__endpoint;

describe("AI Callable 배포 설정 (#112 리뷰 C)", () => {
  it("analyzeTimetable 만 동시 실행 4, 메모리 512MiB, 제한 시간 120초", () => {
    expect(endpoint("analyzeTimetable")).toMatchObject({
      concurrency: 4,
      availableMemoryMb: 512,
      timeoutSeconds: 120,
      maxInstances: 10,
      region: ["asia-northeast3"],
    });
  });

  it.each(["aiPing", "generateProfile", "recommendPlace", "suggestChatReplies"] as const)(
    "%s 는 동시 실행을 지정하지 않고(플랫폼 기본값) 기존 설정 그대로",
    (name) => {
      const e = endpoint(name);
      // 지정하지 않으면 firebase-functions 가 ResetValue(배포 시 플랫폼 기본값)를 넣는다. 숫자가 아니어야 한다.
      expect(typeof e.concurrency).not.toBe("number");
      expect(e).toMatchObject({ availableMemoryMb: 256, timeoutSeconds: 30, maxInstances: 10, region: ["asia-northeast3"] });
    },
  );
});
