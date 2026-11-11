import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, onSnapshot, query, setDoc, updateDoc, where } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { RequestService } from "../src/requests/service";
import { createRequestCallable } from "../src/requests/callable";
import { type CandidateResolver } from "../src/requests/contracts";

// 실제 Callable에는 주입하지 않는다. BT-017 검증 완료 후보를 대신하는 테스트 전용 어댑터.
const projectId = "demo-bintime";
const snapshot = { toUid: "receiver", purpose: "스터디", meetingDate: "2026-11-12", startMinute: 600, endMinute: 690 };
let currentSnapshot = { ...snapshot };
const resolver: CandidateResolver = { async resolve() { return { ...currentSnapshot }; } };
const now = () => new Date("2026-11-11T00:00:00Z");
const app = initializeApp({ projectId }, `requests-test-${randomUUID()}`);
const admin = getFirestore(app);
const service = new RequestService(admin, resolver, now);
let env: RulesTestEnvironment;

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("BT-019 Firestore 에뮬레이터", () => {
  beforeAll(async () => {
    if (!process.env.FIRESTORE_EMULATOR_HOST?.startsWith("127.0.0.1:")
        && !process.env.FIRESTORE_EMULATOR_HOST?.startsWith("localhost:")) throw new Error("local emulator required");
    env = await initializeTestEnvironment({ projectId,
      firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") } });
  });
  beforeEach(async () => { await env.clearFirestore(); currentSnapshot = { ...snapshot }; });
  afterAll(async () => { await env?.cleanup(); await admin.terminate(); await deleteApp(app); });

  it("두 인증 사용자만 동일한 요청을 조회하고 타인·클라이언트 쓰기는 차단한다", async () => {
    const result = await service.create("sender", { candidateId: "candidate-1", clientRequestId: randomUUID() });
    expect(result).toMatchObject({ ...snapshot, fromUid: "sender", status: "PENDING", overlapMinutes: 90, dayOfWeek: 4 });
    const sender = env.authenticatedContext("sender").firestore();
    const receiver = env.authenticatedContext("receiver").firestore();
    const outsider = env.authenticatedContext("outsider").firestore();
    await assertSucceeds(getDoc(doc(sender, "matchRequests", result.id)));
    await assertSucceeds(getDoc(doc(receiver, "matchRequests", result.id)));
    await assertFails(getDoc(doc(outsider, "matchRequests", result.id)));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "matchRequests", result.id)));
    await assertFails(updateDoc(doc(sender, "matchRequests", result.id), { status: "ACCEPTED" }));
    await assertFails(setDoc(doc(sender, "matchRequests", "forged"), { ...result }));
    const sent = await assertSucceeds(getDocs(query(collection(sender, "matchRequests"), where("fromUid", "==", "sender"))));
    const received = await assertSucceeds(getDocs(query(collection(receiver, "matchRequests"), where("toUid", "==", "receiver"))));
    expect(sent.docs[0].id).toBe(received.docs[0].id);
    await assertFails(getDocs(collection(sender, "matchRequests")));
  });

  it("동시 요청·새 멱등 키 연타도 한 건만 생성한다", async () => {
    const outcomes = await Promise.allSettled(Array.from({ length: 6 }, () => service.create("sender", {
      candidateId: "candidate-1", clientRequestId: randomUUID(),
    })));
    expect(outcomes.filter((r) => r.status === "fulfilled")).toHaveLength(1);
    expect((await admin.collection("matchRequests").get()).size).toBe(1);
    for (const outcome of outcomes) if (outcome.status === "rejected") {
      expect(outcome.reason).toMatchObject({ code: "already-exists", details: { reason: "DUPLICATE_PENDING" } });
    }
  });

  it("재시도와 이후 시간표 변경이 생성 당시 스냅샷을 바꾸지 않는다", async () => {
    const input = { candidateId: "candidate-1", clientRequestId: randomUUID() };
    const first = await service.create("sender", input);
    currentSnapshot.startMinute = 900; currentSnapshot.endMinute = 1080;
    const retry = await service.create("sender", input);
    expect(retry).toEqual(first);
    expect((await admin.collection("matchRequests").doc(first.id).get()).data()).toMatchObject(snapshot);
    await expect(service.create("sender", { ...input, candidateId: "another" })).rejects.toMatchObject({
      details: { reason: "IDEMPOTENCY_CONFLICT" },
    });
  });

  it("Callable은 토큰 UID를 사용하고 양측 리스너에 새 요청을 전달한다", async () => {
    const sender = env.authenticatedContext("sender").firestore();
    const receiver = env.authenticatedContext("receiver").firestore();
    const waits = [query(collection(sender, "matchRequests"), where("fromUid", "==", "sender")),
      query(collection(receiver, "matchRequests"), where("toUid", "==", "receiver"))].map((q) => new Promise<string>((resolve, reject) => {
        const timer = setTimeout(() => { stop(); reject(new Error("snapshot timeout")); }, 10_000);
        const stop = onSnapshot(q, (s) => { if (!s.empty) { clearTimeout(timer); stop(); resolve(s.docs[0].id); } }, reject);
      }));
    const callable = createRequestCallable(() => service);
    const result = await callable.run({ auth: { uid: "sender", token: {} },
      data: { candidateId: "candidate-1", clientRequestId: randomUUID() } } as never);
    expect(await Promise.all(waits)).toEqual([result.id, result.id]);
  });
});
