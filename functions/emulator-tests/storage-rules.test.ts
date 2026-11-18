import { readFileSync } from "node:fs";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";

/**
 * B · storage.rules 검증 (BT-014 프로필 사진, BT-025 시간표 이미지).
 * firebase emulators:exec --project demo-bintime --only storage "npm --prefix functions run test:emulator -- emulator-tests/storage-rules.test.ts"
 */
const projectId = "demo-bintime-storage";
const MB = 1024 * 1024;
const jpeg = (bytes = 1024) => new Uint8Array(bytes);
const JPEG = { contentType: "image/jpeg" };
const batch = "0f8fad5b-d9cb-469f-a165-70867728950e";
const image = (uid: string, file = "0.jpg", batchId = batch) => `timetableImages/${uid}/${batchId}/${file}`;

let env: RulesTestEnvironment;
const as = (uid: string) => env.authenticatedContext(uid).storage();
const anonymous = () => env.unauthenticatedContext().storage();
const seed = (path: string) =>
  env.withSecurityRulesDisabled(async (ctx) => {
    await ctx.storage().ref(path).put(jpeg(), JPEG);
  });

describe.skipIf(!process.env.FIREBASE_STORAGE_EMULATOR_HOST)("storage.rules", () => {
  beforeAll(async () => {
    const hostPort = process.env.FIREBASE_STORAGE_EMULATOR_HOST!;
    if (!/^(127\.0\.0\.1|localhost):/.test(hostPort)) throw new Error("local storage emulator required");
    const [host, port] = hostPort.split(":");
    env = await initializeTestEnvironment({
      projectId,
      storage: { host, port: Number(port), rules: readFileSync(new URL("../../storage.rules", import.meta.url), "utf8") },
    });
  });
  beforeEach(async () => {
    await env.clearStorage();
  });
  afterAll(async () => {
    await env?.cleanup();
  });

  describe("BT-025 timetableImages/{uid}/{batchId}/{0~4}.jpg", () => {
    it("본인은 0~4번 JPEG 를 올리고 읽고 지울 수 있다", async () => {
      for (const file of ["0.jpg", "4.jpg"]) {
        await assertSucceeds(as("u1").ref(image("u1", file)).put(jpeg(), JPEG));
      }
      await assertSucceeds(as("u1").ref(image("u1")).getMetadata());
      await assertSucceeds(as("u1").ref(image("u1")).delete());
    });

    it("다른 계정과 비로그인은 읽기·쓰기·삭제가 모두 거부된다", async () => {
      await seed(image("u1"));
      await assertFails(as("u2").ref(image("u1")).getMetadata());
      await assertFails(as("u2").ref(image("u1", "1.jpg")).put(jpeg(), JPEG));
      await assertFails(as("u2").ref(image("u1")).delete());
      await assertFails(anonymous().ref(image("u1")).getMetadata());
      await assertFails(anonymous().ref(image("u1", "1.jpg")).put(jpeg(), JPEG));
      await assertFails(anonymous().ref(image("u1")).delete());
    });

    it("5MB 미만만 허용한다(경계)", async () => {
      await assertSucceeds(as("u1").ref(image("u1", "0.jpg")).put(jpeg(5 * MB - 1), JPEG));
      await assertFails(as("u1").ref(image("u1", "1.jpg")).put(jpeg(5 * MB), JPEG));
    });

    it("image/jpeg 가 아니면 거부한다", async () => {
      for (const contentType of ["image/png", "image/webp", "application/octet-stream"]) {
        await assertFails(as("u1").ref(image("u1")).put(jpeg(), { contentType }));
      }
    });

    it("파일명은 0~4.jpg 만, batchId 는 영문·숫자·하이픈 1~64자만 허용한다", async () => {
      for (const file of ["5.jpg", "10.jpg", "0.png", "0.jpeg", "a.jpg"]) {
        await assertFails(as("u1").ref(image("u1", file)).put(jpeg(), JPEG));
      }
      for (const batchId of ["a_b", "a.b", "x".repeat(65)]) {
        await assertFails(as("u1").ref(image("u1", "0.jpg", batchId)).put(jpeg(), JPEG));
      }
      await assertSucceeds(as("u1").ref(image("u1", "0.jpg", "x".repeat(64))).put(jpeg(), JPEG));
    });

    // 덮어쓰기 차단(update 미허용)은 에뮬레이터가 덮어쓰기도 create 로 판정해 여기서 확인할 수 없다.
    // 운영 게시 후 같은 경로 재업로드가 거부되는지 실기기로 확인한다(storage.rules 주석 참고).

    it("묶음 폴더 밖이나 더 깊은 경로는 거부한다", async () => {
      await assertFails(as("u1").ref("timetableImages/u1/0.jpg").put(jpeg(), JPEG));
      await assertFails(as("u1").ref(`timetableImages/u1/${batch}/sub/0.jpg`).put(jpeg(), JPEG));
    });
  });

  describe("BT-014 profileImages/{uid}/profile.jpg (회귀)", () => {
    const photo = (uid: string) => `profileImages/${uid}/profile.jpg`;

    it("로그인 사용자는 읽고, 본인만 5MB 미만 JPEG 를 쓰고 지운다", async () => {
      await assertSucceeds(as("u1").ref(photo("u1")).put(jpeg(), JPEG));
      await assertSucceeds(as("u1").ref(photo("u1")).put(jpeg(), JPEG)); // 같은 경로 덮어쓰기 허용
      await assertSucceeds(as("u2").ref(photo("u1")).getMetadata());
      await assertFails(anonymous().ref(photo("u1")).getMetadata());
      await assertFails(as("u2").ref(photo("u1")).put(jpeg(), JPEG));
      await assertFails(as("u2").ref(photo("u1")).delete());
      await assertFails(as("u1").ref(photo("u1")).put(jpeg(5 * MB), JPEG));
      await assertFails(as("u1").ref("profileImages/u1/other.jpg").put(jpeg(), JPEG));
      await assertSucceeds(as("u1").ref(photo("u1")).delete());
    });
  });
});
