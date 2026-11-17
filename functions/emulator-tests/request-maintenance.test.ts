import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { assertFails, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, onSnapshot, setDoc } from "firebase/firestore";
import { beforeAll, beforeEach, afterAll, describe, expect, it } from "vitest";
import { RequestService } from "../src/requests/service";
import { RequestMaintenance } from "../src/requests/maintenance";
import { canReceive } from "../src/requests/policy";

const app = initializeApp({ projectId: "demo-bintime-maintenance" }, `maintenance-${randomUUID()}`);
const db = getFirestore(app);
const initial = Date.parse("2026-11-17T00:00:00Z");
let clock = initial;
let window = { meetingDate: "2026-11-17", startMinute: 600, endMinute: 690 };
const service = new RequestService(db, { async resolve(_tx, _from, toUid) { return { toUid, purpose: "스터디", ...window }; } }, () => new Date(clock));
const maintenance = new RequestMaintenance(db, () => new Date(clock));
const create = (from: string, to: string) => service.create(from, { candidateId: to, clientRequestId: randomUUID() });
let env: RulesTestEnvironment;

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("BT-032 정리·만료", () => {
  beforeAll(async () => {
    if (!/^(127\.0\.0\.1|localhost):/.test(process.env.FIRESTORE_EMULATOR_HOST ?? "")) throw new Error("local emulator required");
    env = await initializeTestEnvironment({ projectId: "demo-bintime-maintenance", firestore: {
      rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8"),
    } });
  });
  beforeEach(async () => {
    await env.clearFirestore(); clock = initial; window = { meetingDate: "2026-11-17", startMinute: 600, endMinute: 690 };
    await Promise.all(Array.from({ length: 12 }, (_, i) => `u${i}`).flatMap(uid => [
      db.doc(`users/${uid}`).set({ profileCompleted: true }), db.doc(`publicProfiles/${uid}`).set({ nickname: uid }),
    ]));
  });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("양측 네 방향 상충 요청을 정리하고 비겹침·다른 날짜·스냅샷은 보존한다", async () => {
    const sentA = await create("u0", "u2"), receivedA = await create("u3", "u0");
    const sentB = await create("u1", "u4"), receivedB = await create("u5", "u1");
    window = { ...window, startMinute: 690, endMinute: 780 };
    const adjacent = await create("u0", "u6");
    window = { ...window, meetingDate: "2026-11-18" };
    const otherDay = await create("u1", "u7");
    window = { meetingDate: "2026-11-17", startMinute: 600, endMinute: 690 };
    const selected = await create("u0", "u1");
    await service.respond("u1", { requestId: selected.id, action: "ACCEPTED" });
    for (const item of [sentA, sentB]) expect((await db.doc(`matchRequests/${item.id}`).get()).get("status")).toBe("CANCELED");
    for (const item of [receivedA, receivedB]) expect((await db.doc(`matchRequests/${item.id}`).get()).get("status")).toBe("REJECTED");
    for (const item of [adjacent, otherDay]) expect((await db.doc(`matchRequests/${item.id}`).get()).get("status")).toBe("PENDING");
    expect((await db.doc(`matchRequests/${sentA.id}`).get()).data()).toMatchObject({ purpose: sentA.purpose, startMinute: sentA.startMinute, endMinute: sentA.endMinute });
    expect((await db.doc("requestUserGuards/u0").get()).get("cooldownUntil")).toBeUndefined();
    expect(await canReceive(db, "u10", "u0", new Date(clock), window)).toBe(false);
    expect(await canReceive(db, "u10", "u0", new Date(clock), { ...window, startMinute: 780, endMinute: 870 })).toBe(true);
  });
  it("정확한 종료 시각에 자동 취소하고 기존 직접 취소 제한과 확정 기록을 보존한다", async () => {
    clock = Date.parse("2026-11-17T02:29:50Z");
    const pending = await create("u0", "u1");
    window = { ...window, startMinute: 700, endMinute: 790 };
    const canceled = await create("u0", "u2");
    await service.cancel("u0", canceled.id);
    const until = (await db.doc("requestUserGuards/u0").get()).get("cooldownUntil");
    const accepted = await create("u3", "u4");
    await service.respond("u4", { requestId: accepted.id, action: "ACCEPTED" });
    clock += 10_000;
    expect((await maintenance.runBatch()).expired).toBe(1);
    expect((await db.doc(`matchRequests/${pending.id}`).get()).data()).toMatchObject({ status: "CANCELED", terminalReason: "EXPIRED" });
    expect((await db.doc(`matchRequests/${accepted.id}`).get()).get("status")).toBe("ACCEPTED");
    expect((await db.doc("requestUserGuards/u0").get()).get("cooldownUntil")).toEqual(until);
    expect((await maintenance.runBatch()).expired).toBe(0);
  });
  it("만료와 직접 취소 경합은 상태·개수를 중복 처리하지 않는다", async () => {
    const pending = await create("u0", "u1"); clock = Date.parse("2026-11-17T02:30:00Z");
    await Promise.all([maintenance.expire(pending.id), service.cancel("u0", pending.id)]);
    expect((await db.doc(`matchRequests/${pending.id}`).get()).data()).toMatchObject({ status: "CANCELED", terminalReason: "EXPIRED" });
    expect((await db.doc("requestUserGuards/u0").get()).get("cooldownUntil")).toBeUndefined();
  });
  it("기존 대량 상충 요청은 영속 작업으로 나누고 수락 이후 생성된 요청은 건드리지 않는다", async () => {
    const selected = await create("u0", "u1");
    await Promise.all(Array.from({ length: 85 }, (_, i) => db.doc(`matchRequests/legacy-${i}`).set({
      ...window, fromUid: "u0", toUid: `legacy-peer-${i}`, purpose: "스터디", status: "PENDING", createdAt: Timestamp.fromMillis(clock), updatedAt: Timestamp.fromMillis(clock),
    })));
    await service.respond("u1", { requestId: selected.id, action: "ACCEPTED" });
    expect((await db.doc(`matchCleanupJobs/${selected.id}`).get()).get("status")).toBe("PENDING");
    await db.doc("matchRequests/newer").set({ ...window, fromUid: "u0", toUid: "u10", status: "PENDING", createdAt: Timestamp.fromMillis(clock + 1) });
    for (let i = 0; i < 3; i++) await maintenance.runBatch();
    expect((await db.doc(`matchCleanupJobs/${selected.id}`).get()).get("status")).toBe("DONE");
    const legacy = await db.collection("matchRequests").where("toUid", ">=", "legacy-peer-").where("toUid", "<=", "legacy-peer-\uf8ff").get();
    expect(legacy.size).toBe(85);
    expect(legacy.docs.every(d => d.get("status") === "CANCELED")).toBe(true);
    expect((await db.doc("matchRequests/newer").get()).get("status")).toBe("PENDING");
  });
  it("복구 불가능한 정리 작업은 세 번 후 FAILED로 보존하고 무한 재시도하지 않는다", async () => {
    await db.doc("matchCleanupJobs/missing").set({ status: "PENDING", attempts: 0 });
    for (let i = 0; i < 3; i++) expect((await maintenance.runBatch()).failed).toBe(1);
    expect((await db.doc("matchCleanupJobs/missing").get()).data()).toMatchObject({ status: "FAILED", attempts: 3 });
    expect((await maintenance.runBatch()).failed).toBe(0);
  });
  it("상충 정리가 상대방 리스너에 전파되고 내부 작업은 앱에서 접근할 수 없다", async () => {
    const pending = await create("u0", "u2");
    const selected = await create("u0", "u1");
    const client = env.authenticatedContext("u2").firestore();
    const changed = new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => { stop(); reject(new Error("cleanup listener timeout")); }, 10_000);
      const stop = onSnapshot(doc(client, "matchRequests", pending.id), snapshot => {
        if (snapshot.get("status") === "CANCELED") { clearTimeout(timer); stop(); resolve(snapshot.get("terminalReason")); }
      }, reject);
    });
    await service.respond("u1", { requestId: selected.id, action: "ACCEPTED" });
    expect(await changed).toBe("MATCH_CONFLICT");
    await assertFails(getDoc(doc(client, "matchCleanupJobs", selected.id)));
    await assertFails(setDoc(doc(client, "matchCleanupJobs", selected.id), { status: "DONE" }));
  });
});
