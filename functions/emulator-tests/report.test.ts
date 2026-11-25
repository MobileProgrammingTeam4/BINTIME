import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getDatabase } from "firebase-admin/database";
import { assertFails, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, setDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { BlockService } from "../src/safety/block";
import { reportKey, ReportService, type ReportInput } from "../src/safety/report";
import { createReportCallable } from "../src/safety/reportCallable";

/**
 * BT-051 신고 접수. 로컬 Firestore·RTDB 에뮬레이터에서만 실행한다.
 *   firebase emulators:exec --project demo-bintime --only firestore,database "npm --prefix functions run test:emulator"
 */
const projectId = "demo-bintime-report";
const app = initializeApp({ projectId, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? "127.0.0.1:9000"}?ns=${projectId}` }, `report-${randomUUID()}`);
const db = getFirestore(app), rtdb = getDatabase(app);
const NOW = Date.parse("2026-11-25T03:00:00Z");
const ME = "u0", OTHER = "u1", THIRD = "u2";
const MATCH = "a".repeat(64), OTHERS_MATCH = "b".repeat(64);
let env: RulesTestEnvironment;
const reports = () => new ReportService(db, () => new Date(NOW));
const blocks = () => new BlockService(db, rtdb, () => new Date(NOW));
const input = (over: Partial<ReportInput> = {}): ReportInput =>
  ({ targetUid: OTHER, matchId: MATCH, reasons: ["NO_SHOW"], detail: null, alsoBlock: false, ...over });
const auth = (uid: string) => ({ uid, token: { email: `${uid}@school.ac.kr`, email_verified: true } });
const callable = (blockService: Pick<BlockService, "block"> = blocks()) =>
  createReportCallable(() => ({ reports: reports(), blocks: blockService }), () => [".ac.kr"]);

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST)("BT-051 신고 접수", () => {
  beforeAll(async () => {
    for (const host of [process.env.FIRESTORE_EMULATOR_HOST, process.env.FIREBASE_DATABASE_EMULATOR_HOST]) {
      if (!/^(127\.0\.0\.1|localhost):/.test(host ?? "")) throw new Error("local emulators required");
    }
    const [host, port] = process.env.FIREBASE_DATABASE_EMULATOR_HOST!.split(":");
    env = await initializeTestEnvironment({ projectId,
      firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
      database: { host, port: Number(port), rules: readFileSync(new URL("../../database.rules.json", import.meta.url), "utf8") } });
  });
  beforeEach(async () => {
    await env.clearFirestore(); await env.clearDatabase();
    for (const uid of [ME, OTHER, THIRD]) await db.doc(`users/${uid}`).set({ uid, nickname: uid, department: "컴퓨터공학과" });
    for (const [id, participants] of [[MATCH, [ME, OTHER]], [OTHERS_MATCH, [OTHER, THIRD]]] as const) {
      await db.doc(`matches/${id}`).set({ participantUids: participants, status: "ENDED", meetingDate: "2026-11-24",
        startMinute: 600, endMinute: 700, createdAt: Timestamp.fromMillis(NOW) });
    }
  });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("신고자·대상·매칭 ID·사유만 저장한다(개인정보 최소 수집)", async () => {
    const id = await reports().submit(ME, input({ reasons: ["SPAM", "NO_SHOW"], detail: "  약속 장소에 오지 않았어요  " }));
    expect(id).toBe(reportKey(ME, OTHER, MATCH));
    expect((await db.doc(`reports/${id}`).get()).data()).toEqual({ reporterUid: ME, targetUid: OTHER, matchId: MATCH,
      reasons: ["NO_SHOW", "SPAM"], detail: "약속 장소에 오지 않았어요", alsoBlock: false, status: "SUBMITTED",
      createdAt: Timestamp.fromMillis(NOW) });
  });

  it("같은 매칭은 한 번만 신고하고, 다른 매칭·매칭 밖 신고는 따로 받는다", async () => {
    await reports().submit(ME, input());
    await expect(reports().submit(ME, input({ reasons: ["SPAM"] }))).rejects.toMatchObject({
      code: "already-exists", details: { reason: "ALREADY_REPORTED" } });
    await reports().submit(ME, input({ matchId: null }));
    await reports().submit(OTHER, input({ targetUid: ME })); // 상대도 나를 따로 신고할 수 있다
    expect((await db.collection("reports").get()).size).toBe(3);
    // 동시에 두 번 보내도 한 건
    await env.clearFirestore();
    await db.doc(`users/${OTHER}`).set({ uid: OTHER });
    const results = await Promise.allSettled([reports().submit(ME, input({ matchId: null })), reports().submit(ME, input({ matchId: null }))]);
    expect(results.filter(r => r.status === "fulfilled")).toHaveLength(1);
    expect((await db.collection("reports").get()).size).toBe(1);
  });

  it("함께한 매칭의 상대만 신고할 수 있고, 자기 자신·없는 사용자는 거부한다", async () => {
    await expect(reports().submit(ME, input({ matchId: OTHERS_MATCH }))).rejects.toMatchObject({ code: "permission-denied" });
    await expect(reports().submit(ME, input({ targetUid: THIRD }))).rejects.toMatchObject({ code: "permission-denied" });
    await expect(reports().submit(ME, input({ matchId: "c".repeat(64) }))).rejects.toMatchObject({ code: "permission-denied" });
    await expect(reports().submit(ME, input({ targetUid: ME, matchId: null }))).rejects.toMatchObject({ code: "invalid-argument" });
    await expect(reports().submit(ME, input({ targetUid: "ghost", matchId: null }))).rejects.toMatchObject({ code: "not-found" });
    // 탈퇴한 상대도 함께한 매칭 안에서는 신고할 수 있다
    await db.doc(`users/${OTHER}`).delete();
    await expect(reports().submit(ME, input())).resolves.toBe(reportKey(ME, OTHER, MATCH));
    expect((await db.collection("reports").get()).size).toBe(1);
  });

  it("신고 기록은 신고자·대상·제3자 모두 읽거나 쓸 수 없다", async () => {
    const id = await reports().submit(ME, input());
    for (const uid of [ME, OTHER, THIRD]) {
      const client = env.authenticatedContext(uid).firestore();
      await assertFails(getDoc(doc(client, `reports/${id}`)));
      await assertFails(getDocs(collection(client, "reports")));
      await assertFails(setDoc(doc(client, `reports/${uid}-fake`), { reporterUid: uid, targetUid: OTHER }));
    }
  });

  it("Callable: 입력을 검증하고, 함께 차단을 고르면 신고 뒤 차단까지 한다", async () => {
    const call = callable();
    for (const data of [
      { ...input(), reasons: [] }, { ...input(), reasons: ["HACKING"] }, { ...input(), reasons: ["SPAM", "SPAM"] },
      { ...input(), detail: "가".repeat(501) }, { ...input(), reporterUid: OTHER }, { ...input(), matchId: "../x" },
    ]) {
      await expect(call.run({ auth: auth(ME), data } as never)).rejects.toMatchObject({ code: "invalid-argument" });
    }
    await expect(call.run({ data: input() } as never)).rejects.toMatchObject({ code: "unauthenticated" });

    await expect(call.run({ auth: auth(ME), data: input({ alsoBlock: true }) } as never))
      .resolves.toEqual({ reportId: reportKey(ME, OTHER, MATCH), blocked: true });
    expect((await db.doc(`users/${ME}/blockedUsers/${OTHER}`).get()).exists).toBe(true);
    // 함께 차단을 고르지 않으면 차단하지 않는다
    await call.run({ auth: auth(OTHER), data: input({ targetUid: ME }) } as never);
    expect((await db.doc(`users/${OTHER}/blockedUsers/${ME}`).get()).exists).toBe(false);
  });

  it("이미 신고했어도 함께 차단은 해 주고, 차단만 실패하면 신고는 남긴다", async () => {
    await callable().run({ auth: auth(ME), data: input() } as never);
    await expect(callable().run({ auth: auth(ME), data: input({ alsoBlock: true }) } as never))
      .rejects.toMatchObject({ code: "already-exists", details: { reason: "ALREADY_REPORTED", blocked: true } });
    expect((await db.doc(`users/${ME}/blockedUsers/${OTHER}`).get()).exists).toBe(true);

    const broken = callable({ block: async () => { throw new Error("rtdb down"); } });
    await expect(broken.run({ auth: auth(ME), data: input({ matchId: null, alsoBlock: true }) } as never))
      .resolves.toEqual({ reportId: reportKey(ME, OTHER, null), blocked: false });
    expect((await db.doc(`reports/${reportKey(ME, OTHER, null)}`).get()).exists).toBe(true);
  });
});
