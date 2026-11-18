import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, onSnapshot, query, setDoc, updateDoc, where } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { RequestService } from "../src/requests/service";
import { cancelRequestCallable, createRequestCallable } from "../src/requests/callable";
import { type CandidateResolver } from "../src/requests/contracts";

// 실제 Callable에는 주입하지 않는다. BT-017 검증 완료 후보를 대신하는 테스트 전용 어댑터.
const projectId = "demo-bintime";
const snapshot = { toUid: "receiver", purpose: "스터디", meetingDate: "2026-11-12", startMinute: 600, endMinute: 690 };
let currentSnapshot = { ...snapshot };
const resolver: CandidateResolver = { async resolve() { return { ...currentSnapshot }; } };
let clock = Date.parse("2026-11-11T00:00:00Z");
const now = () => new Date(clock);
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
  beforeEach(async () => { await env.clearFirestore(); currentSnapshot = { ...snapshot }; clock = Date.parse("2026-11-11T00:00:00Z"); });
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
    const callable = createRequestCallable(() => service, () => [".ac.kr"]);
    const result = await callable.run({ auth: { uid: "sender", token: { email: "test@school.ac.kr", email_verified: true } },
      data: { candidateId: "candidate-1", clientRequestId: randomUUID() } } as never);
    expect(await Promise.all(waits)).toEqual([result.id, result.id]);
  });

  it("발신자 취소가 양측 리스너에 반영되고 스냅샷은 보존된다", async () => {
    const original = await service.create("sender", { candidateId: "candidate-1", clientRequestId: randomUUID() });
    const waits = ["sender", "receiver"].map((uid, i) => new Promise<string>((resolve, reject) => {
      const db = env.authenticatedContext(uid).firestore();
      const q = query(collection(db, "matchRequests"), where(i === 0 ? "fromUid" : "toUid", "==", uid));
      const timer = setTimeout(() => { stop(); reject(new Error("cancel snapshot timeout")); }, 10_000);
      const stop = onSnapshot(q, (s) => {
        const canceled = s.docs.find((d) => d.id === original.id && d.data().status === "CANCELED");
        if (canceled) { clearTimeout(timer); stop(); resolve(canceled.id); }
      }, reject);
    }));
    const callable = cancelRequestCallable(() => service, () => [".ac.kr"]);
    const canceled = await callable.run({ auth: { uid: "sender", token: { email: "test@school.ac.kr", email_verified: true } }, data: { requestId: original.id } } as never);
    expect(canceled).toMatchObject({ ...snapshot, status: "CANCELED", overlapMinutes: 90 });
    expect(await Promise.all(waits)).toEqual([original.id, original.id]);
    expect(await service.cancel("sender", original.id)).toEqual(canceled);
    expect((await admin.collection("matchRequests").doc(original.id).get()).data()).toMatchObject(snapshot);
    clock += 60_000;
    const replacement = await service.create("sender", { candidateId: "candidate-1", clientRequestId: randomUUID() });
    expect(replacement.id).not.toBe(original.id);
    expect((await admin.collection("matchRequests").get()).size).toBe(2);
  });

  it("수신자·제3자는 취소할 수 없고 처리된 요청도 바꿀 수 없다", async () => {
    const original = await service.create("sender", { candidateId: "candidate-1", clientRequestId: randomUUID() });
    for (const uid of ["receiver", "outsider"]) {
      await expect(service.cancel(uid, original.id)).rejects.toMatchObject({ code: "permission-denied" });
    }
    for (const status of ["ACCEPTED", "REJECTED"]) {
      await admin.collection("matchRequests").doc(original.id).update({ status });
      await expect(service.cancel("sender", original.id)).rejects.toMatchObject({
        code: "failed-precondition", details: { reason: "REQUEST_NOT_PENDING" },
      });
      expect((await admin.collection("matchRequests").doc(original.id).get()).get("status")).toBe(status);
    }
    await expect(service.cancel("sender", "f".repeat(64))).rejects.toMatchObject({ code: "not-found" });
  });

  it("동시 취소는 멱등이며 수락/취소 경합도 한 상태로 직렬화된다", async () => {
    const original = await service.create("sender", { candidateId: "candidate-1", clientRequestId: randomUUID() });
    const canceled = await Promise.all(Array.from({ length: 4 }, () => service.cancel("sender", original.id)));
    for (const result of canceled) expect(result).toEqual(canceled[0]);
    clock += 60_000;
    const next = await service.create("sender", { candidateId: "candidate-2", clientRequestId: randomUUID() });
    const ref = admin.collection("matchRequests").doc(next.id);
    // BT-031의 수락 구현 대신 동일 문서에서 PENDING을 확인하는 테스트 트랜잭션만 사용한다.
    const accept = admin.runTransaction(async (tx) => {
      const before = await tx.get(ref);
      if (before.get("status") === "PENDING") tx.update(ref, { status: "ACCEPTED" });
    });
    const outcomes = await Promise.allSettled([accept, service.cancel("sender", next.id)]);
    const status = (await ref.get()).get("status");
    expect(["ACCEPTED", "CANCELED"]).toContain(status);
    const cancelResult = outcomes[1];
    if (status === "ACCEPTED") {
      expect(cancelResult.status).toBe("rejected");
    } else {
      expect(cancelResult.status).toBe("fulfilled");
    }
  });
});
