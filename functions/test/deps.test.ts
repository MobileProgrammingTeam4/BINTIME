import { deleteApp, getApps, initializeApp } from "firebase-admin/app";
import { afterAll, afterEach, describe, expect, it } from "vitest";
import { defaultDeps, readStorageImage, type StorageImageFile } from "../src/ai/deps";
import { runAiTask } from "../src/ai/gateway";
import { analyzeTimetableTask } from "../src/ai/tasks/analyzeTimetable";
import { ctx, expectReason, makeDeps } from "./helpers";

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

/** 가짜 Storage 파일: 열기·메타데이터·다운로드 횟수를 센다. */
function fakeStorage(metadata: { size?: string | number | null; contentType?: string | null }) {
  const counts = { opens: 0, metadata: 0, downloads: 0 };
  const open = (): StorageImageFile => {
    counts.opens++;
    return {
      getMetadata: async () => {
        counts.metadata++;
        return [metadata];
      },
      download: async () => {
        counts.downloads++;
        return [Buffer.from([0xff, 0xd8, 0xff, 1])];
      },
    };
  };
  return { open, counts };
}

const imagePaths = ["timetableImages/uid-me/batch-1/0.jpg"];

describe("readStorageImage", () => {
  const saved = {
    emulator: process.env.FUNCTIONS_EMULATOR,
    storageHost: process.env.FIREBASE_STORAGE_EMULATOR_HOST,
  };
  const restore = (key: string, value: string | undefined) => {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  };
  afterEach(() => {
    restore("FUNCTIONS_EMULATOR", saved.emulator);
    restore("FIREBASE_STORAGE_EMULATOR_HOST", saved.storageHost);
  });

  it("에뮬레이터인데 Storage 에뮬레이터 주소가 없으면 운영 버킷을 읽지 않는다(읽기·차감·Gemini 0회)", async () => {
    process.env.FUNCTIONS_EMULATOR = "true";
    delete process.env.FIREBASE_STORAGE_EMULATOR_HOST;
    const storage = fakeStorage({ size: 4, contentType: "image/jpeg" });
    const deps = makeDeps({ mode: "live", steps: [{ text: "{}" }] });
    deps.readImage = (_path, maxBytes) => readStorageImage(storage.open, maxBytes);

    const error = await expectReason(runAiTask(analyzeTimetableTask, { imagePaths }, ctx(), deps), "AI_UNAVAILABLE");
    expect(error.retryable).toBe(false);
    expect(storage.counts).toEqual({ opens: 0, metadata: 0, downloads: 0 });
    expect(deps.usageStore.counts.size).toBe(0);
    expect(deps.client.calls).toHaveLength(0);
  });

  it("메타데이터에 크기가 없으면 내려받지 않고 INVALID_INPUT(차감·Gemini 0회)", async () => {
    const storage = fakeStorage({ contentType: "image/jpeg" });
    await expect(readStorageImage(storage.open, 100, {})).resolves.toEqual({ contentType: "image/jpeg", size: Number.NaN });
    expect(storage.counts.downloads).toBe(0);

    const deps = makeDeps({ mode: "live", steps: [{ text: "{}" }] });
    deps.readImage = (_path, maxBytes) => readStorageImage(storage.open, maxBytes, {});
    const error = await expectReason(runAiTask(analyzeTimetableTask, { imagePaths }, ctx(), deps), "INVALID_INPUT");
    expect(error.fields).toEqual(["imagePaths.0"]);
    expect(storage.counts.downloads).toBe(0);
    expect(deps.usageStore.counts.size).toBe(0);
    expect(deps.client.calls).toHaveLength(0);
  });
});
