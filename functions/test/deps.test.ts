import { deleteApp, getApps, initializeApp } from "firebase-admin/app";
import { afterAll, describe, expect, it } from "vitest";
import { defaultDeps } from "../src/ai/deps";

describe("defaultDeps", () => {
  afterAll(async () => {
    await Promise.all(getApps().map((app) => deleteApp(app)));
  });

  it("이름 있는 Admin 앱만 먼저 있어도 기본 앱을 만들어 Firestore 를 준비한다", () => {
    // 배포 환경에서 firebase-functions 가 토큰 검증용으로 만드는 앱을 흉내 낸다.
    initializeApp({ projectId: "demo-bintime" }, "functions-internal");
    expect(() => defaultDeps()).not.toThrow();
    expect(getApps().map((app) => app.name)).toContain("[DEFAULT]");
  });
});
