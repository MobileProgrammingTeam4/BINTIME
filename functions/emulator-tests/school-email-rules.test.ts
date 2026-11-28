import { readFileSync } from "node:fs";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";

/**
 * #135 1번: 공개 프로필·프로필 사진은 인증된 학교 이메일 계정만 읽는다.
 * firebase emulators:exec --project demo-bintime --only firestore,storage "npm --prefix functions run test:emulator -- emulator-tests/school-email-rules.test.ts"
 * 데이터는 모두 합성 fixture 다. 운영 프로젝트·실제 계정을 쓰지 않는다.
 */
const projectId = "demo-bintime-school-email";
const PROFILE = "publicProfiles/fixture-student";
const PHOTO = "profileImages/fixture-student/profile.jpg";
const JPEG = { contentType: "image/jpeg" };

type Token = { email?: string; email_verified?: boolean };
const accounts: Record<string, Token> = {
  school: { email: "reader@school.ac.kr", email_verified: true },
  schoolUpper: { email: "Reader@School.AC.KR", email_verified: true },
  schoolEdu: { email: "reader@uni.edu", email_verified: true },
  schoolUnverified: { email: "reader@school.ac.kr", email_verified: false },
  external: { email: "outside@example.net", email_verified: false },
  externalVerified: { email: "outside@example.net", email_verified: true },
  lookalike: { email: "x@school.ac.kr.example.com", email_verified: true },
  noEmail: {},
};

let env: RulesTestEnvironment;
const ctx = (name: keyof typeof accounts) => env.authenticatedContext(`fixture-${name}`, accounts[name]);

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_STORAGE_EMULATOR_HOST)(
  "#135 학교 인증 읽기 권한", () => {
    beforeAll(async () => {
      const local = /^(127\.0\.0\.1|localhost):/;
      if (!local.test(process.env.FIRESTORE_EMULATOR_HOST!) || !local.test(process.env.FIREBASE_STORAGE_EMULATOR_HOST!)) {
        throw new Error("local emulators required");
      }
      const [host, port] = process.env.FIREBASE_STORAGE_EMULATOR_HOST!.split(":");
      env = await initializeTestEnvironment({
        projectId,
        firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
        storage: { host, port: Number(port), rules: readFileSync(new URL("../../storage.rules", import.meta.url), "utf8") },
      });
    });
    beforeEach(async () => {
      await env.clearFirestore();
      await env.clearStorage();
      await env.withSecurityRulesDisabled(async (admin) => {
        await setDoc(doc(admin.firestore(), PROFILE), { uid: "fixture-student", nickname: "fixture", department: "cs" });
        await admin.storage().ref(PHOTO).put(new Uint8Array([0xff, 0xd8, 0xff, 0xd9]), JPEG);
      });
    });
    afterAll(async () => {
      await env?.cleanup();
    });

    const readProfile = (c: ReturnType<typeof ctx>) => getDoc(doc(c.firestore(), PROFILE));
    const readPhoto = (c: ReturnType<typeof ctx>) => c.storage().ref(PHOTO).getMetadata();

    it("인증된 학교 계정(.ac.kr·.edu, 대소문자 무관)은 공개 프로필과 사진을 읽는다", async () => {
      for (const name of ["school", "schoolUpper", "schoolEdu"] as const) {
        await assertSucceeds(readProfile(ctx(name)));
        await assertSucceeds(readPhoto(ctx(name)));
      }
    });

    it("익명·이메일 없음·미인증 학교 계정·외부 도메인·유사 도메인은 거부된다", async () => {
      await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), PROFILE)));
      await assertFails(env.unauthenticatedContext().storage().ref(PHOTO).getMetadata());
      for (const name of ["noEmail", "schoolUnverified", "external", "externalVerified", "lookalike"] as const) {
        await assertFails(readProfile(ctx(name)));
        await assertFails(readPhoto(ctx(name)));
      }
    });

    it("쓰기 권한은 바뀌지 않는다: 본인 쓰기 허용, 타인 쓰기 거부", async () => {
      const own = env.authenticatedContext("fixture-student", accounts.school);
      await assertSucceeds(setDoc(doc(own.firestore(), PROFILE), { uid: "fixture-student", nickname: "new", department: "cs" }));
      await assertSucceeds(own.storage().ref(PHOTO).put(new Uint8Array(16), JPEG));
      await assertFails(setDoc(doc(ctx("school").firestore(), PROFILE), { uid: "fixture-student", nickname: "x", department: "cs" }));
      await assertFails(ctx("school").storage().ref(PHOTO).put(new Uint8Array(16), JPEG));
    });
  });
