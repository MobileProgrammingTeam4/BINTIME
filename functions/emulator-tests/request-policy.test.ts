import { randomUUID } from "node:crypto";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { beforeAll, beforeEach, afterAll, describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { RequestService } from "../src/requests/service";
import { canReceive } from "../src/requests/policy";

const projectId = "demo-bintime-policy";
const initial = Date.parse("2026-11-17T00:00:00Z");
let clock = initial;
let start = 600;
let end = 690;
const app = initializeApp({ projectId }, `policy-${randomUUID()}`);
const db = getFirestore(app);
const service = new RequestService(db, { async resolve(_tx, _uid, toUid) {
  return { toUid, purpose: "스터디", meetingDate: "2026-11-17", startMinute: start, endMinute: end };
} }, () => new Date(clock));
const create = (from: string, to: string) => service.create(from, { candidateId: to, clientRequestId: randomUUID() });
let env: RulesTestEnvironment;

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("BT-031 요청 정책과 동시성", () => {
  beforeAll(async () => {
    if (!/^(127\.0\.0\.1|localhost):/.test(process.env.FIRESTORE_EMULATOR_HOST ?? "")) throw new Error("local emulator required");
    env = await initializeTestEnvironment({ projectId, firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") } });
  });
  beforeEach(async () => {
    await env.clearFirestore(); clock = initial; start = 600; end = 690;
    await Promise.all(Array.from({ length: 12 }, (_, i) => `u${i}`).flatMap(uid => [
      db.doc(`users/${uid}`).set({ profileCompleted: true }), db.doc(`publicProfiles/${uid}`).set({ nickname: uid }),
    ]));
  });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("동시 신규 신청은 발신 3개만 성공한다", async () => {
    const results = await Promise.allSettled(Array.from({ length: 8 }, (_, i) => create("u0", `u${i + 1}`)));
    expect(results.filter(r => r.status === "fulfilled")).toHaveLength(3);
    expect((await db.collection("matchRequests").get()).size).toBe(3);
  });
  it("여러 발신자가 동시에 신청해도 수신 5개만 성공한다", async () => {
    const results = await Promise.allSettled(Array.from({ length: 8 }, (_, i) => create(`u${i + 1}`, "u0")));
    expect(results.filter(r => r.status === "fulfilled")).toHaveLength(5);
    expect(await canReceive(db, "u10", "u0", new Date(clock))).toBe(false);
  });
  it("도입 전 상한 초과 요청은 보존하고 신규 생성만 막는다", async () => {
    for (let i = 1; i <= 6; i++) await db.doc(`matchRequests/legacy${i}`).set({
      fromUid: "u0", toUid: `u${i}`, status: "PENDING", meetingDate: "2026-11-17", startMinute: 600, endMinute: 690,
    });
    await expect(create("u0", "u10")).rejects.toMatchObject({ details: { reason: "SENT_LIMIT" } });
    expect((await db.collection("matchRequests").get()).size).toBe(6);
  });
  it("양방향 동시 신청은 한 건만 생성하고 역방향 검색에서 제외한다", async () => {
    const results = await Promise.allSettled([create("u0", "u1"), create("u1", "u0")]);
    expect(results.filter(r => r.status === "fulfilled")).toHaveLength(1);
    const winner = results.find(r => r.status === "fulfilled") as PromiseFulfilledResult<Awaited<ReturnType<typeof create>>>;
    expect(await canReceive(db, winner.value.toUid, winner.value.fromUid, new Date(clock))).toBe(false);
    const loser = results.find(r => r.status === "rejected") as PromiseRejectedResult;
    expect(loser.reason).toMatchObject({ details: { reason: "REVERSE_PENDING" } });
  });
  it("직접 취소는 모든 상대에 60초 제한, 재시도는 연장하지 않는다", async () => {
    const input = { candidateId: "u1", clientRequestId: randomUUID() };
    const first = await service.create("u0", input);
    const canceled = await service.cancel("u0", first.id);
    clock += 30_000;
    expect(await service.cancel("u0", first.id)).toEqual(canceled);
    expect((await service.create("u0", input)).status).toBe("CANCELED");
    await expect(create("u0", "u2")).rejects.toMatchObject({ details: { reason: "REQUEST_COOLDOWN" } });
    clock = initial + 59_999;
    await expect(create("u0", "u2")).rejects.toMatchObject({ details: { reason: "REQUEST_COOLDOWN" } });
    clock++;
    expect((await create("u0", "u2")).status).toBe("PENDING");
  });
  it("수락·거절은 수신자만, 수락 재시도에도 매칭 한 건과 스냅샷 유지", async () => {
    const first = await create("u0", "u1");
    for (const uid of ["u0", "u2"]) await expect(service.respond(uid, { requestId: first.id, action: "ACCEPTED" }))
      .rejects.toMatchObject({ details: { reason: "NOT_RECIPIENT" } });
    const accepted = await service.respond("u1", { requestId: first.id, action: "ACCEPTED" });
    start = 900; end = 990;
    expect(await service.respond("u1", { requestId: first.id, action: "ACCEPTED" })).toEqual(accepted);
    expect((await db.collection("matches").get()).size).toBe(1);
    expect((await db.doc(`matches/${first.id}`).get()).data()).toMatchObject({ startMinute: 600, endMinute: 690, chatReady: false, requestId: first.id });
  });
  it("서로 다른 요청을 동시에 수락해도 상충 매칭은 한 건이다", async () => {
    const a = await create("u0", "u2"); const b = await create("u1", "u2");
    const results = await Promise.allSettled([a, b].map(r => service.respond("u2", { requestId: r.id, action: "ACCEPTED" })));
    expect(results.filter(r => r.status === "fulfilled")).toHaveLength(1);
    expect((await db.collection("matches").get()).size).toBe(1);
  });
  it("수락·취소 경합은 하나의 종결 상태만 남는다", async () => {
    const first = await create("u0", "u1");
    await Promise.allSettled([service.cancel("u0", first.id), service.respond("u1", { requestId: first.id, action: "ACCEPTED" })]);
    const status = (await db.doc(`matchRequests/${first.id}`).get()).get("status");
    expect(["ACCEPTED", "CANCELED"]).toContain(status);
    expect((await db.doc(`matches/${first.id}`).get()).exists).toBe(status === "ACCEPTED");
  });
  it("종료 시각과 같은 시점에는 수락 대신 만료 취소하고 제한을 만들지 않는다", async () => {
    const first = await create("u0", "u1");
    clock = Date.parse("2026-11-17T02:30:00Z");
    expect((await service.respond("u1", { requestId: first.id, action: "ACCEPTED" })).status).toBe("CANCELED");
    expect((await db.doc(`matchRequests/${first.id}`).get()).get("terminalReason")).toBe("EXPIRED");
    expect((await db.doc("requestUserGuards/u0").get()).get("cooldownUntil")).toBeUndefined();
  });
  it("양방향 차단 또는 계정 소멸 뒤에는 수락하지 않는다", async () => {
    const first = await create("u0", "u1");
    await db.doc("users/u1/blockedUsers/u0").set({});
    await expect(service.respond("u1", { requestId: first.id, action: "ACCEPTED" })).rejects.toMatchObject({ details: { reason: "PARTICIPANT_UNAVAILABLE" } });
    await db.doc("users/u1/blockedUsers/u0").delete(); await db.doc("users/u0").delete();
    await expect(service.respond("u1", { requestId: first.id, action: "ACCEPTED" })).rejects.toMatchObject({ details: { reason: "PARTICIPANT_UNAVAILABLE" } });
  });
  it("오늘 만료된 대기는 상한을 차지하지 않고 미래의 오전 요청은 포함한다", async () => {
    for (let i = 1; i <= 4; i++) await db.doc(`matchRequests/old${i}`).set({ fromUid: "u0", toUid: `u${i}`, status: "PENDING", meetingDate: "2026-11-17", startMinute: 300, endMinute: 540 });
    expect((await create("u0", "u5")).status).toBe("PENDING");
    for (let i = 6; i <= 7; i++) await db.doc(`matchRequests/future${i}`).set({ fromUid: "u0", toUid: `u${i}`, status: "PENDING", meetingDate: "2026-11-18", startMinute: 60, endMinute: 120 });
    await expect(create("u0", "u8")).rejects.toMatchObject({ details: { reason: "SENT_LIMIT" } });
  });
  it("정확히 맞닿는 약속은 충돌하지 않는다", async () => {
    const a = await create("u0", "u2");
    await service.respond("u2", { requestId: a.id, action: "ACCEPTED" });
    start = 690; end = 780;
    const b = await create("u1", "u2");
    expect((await service.respond("u2", { requestId: b.id, action: "ACCEPTED" })).status).toBe("ACCEPTED");
  });
  it("이전 버전의 잠금 없는 역방향 요청도 보존하고 신규 역방향을 막는다", async () => {
    await db.doc("matchRequests/legacy-reverse").set({ fromUid: "u1", toUid: "u0", status: "PENDING", meetingDate: "2026-11-17", startMinute: 600, endMinute: 690 });
    await expect(create("u0", "u1")).rejects.toMatchObject({ details: { reason: "REVERSE_PENDING" } });
    expect((await db.collection("matchRequests").get()).size).toBe(1);
  });
  it("확정 매칭은 참여자만 읽고 잠금·상태 쓰기는 모두 차단한다", async () => {
    const first = await create("u0", "u1");
    await service.respond("u1", { requestId: first.id, action: "ACCEPTED" });
    const mine = env.authenticatedContext("u0").firestore();
    const peer = env.authenticatedContext("u1").firestore();
    const other = env.authenticatedContext("u2").firestore();
    await assertSucceeds(getDoc(doc(mine, "matches", first.id)));
    await assertSucceeds(getDoc(doc(peer, "matches", first.id)));
    await assertFails(getDoc(doc(other, "matches", first.id)));
    await assertFails(setDoc(doc(mine, "matches", first.id), { status: "ENDED" }));
    await assertFails(getDoc(doc(mine, "requestUserGuards/u0")));
    await assertFails(setDoc(doc(mine, "requestUserGuards/u0"), { revision: 0 }));
  });
  it("저장된 요청 스냅샷이 잘못됐으면 확정 매칭을 만들지 않는다", async () => {
    const first = await create("u0", "u1");
    await db.doc(`matchRequests/${first.id}`).update({ startMinute: "invalid" });
    await expect(service.respond("u1", { requestId: first.id, action: "ACCEPTED" })).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
    expect((await db.doc(`matchRequests/${first.id}`).get()).get("status")).toBe("PENDING");
    expect((await db.doc(`matches/${first.id}`).get()).exists).toBe(false);
  });
});
