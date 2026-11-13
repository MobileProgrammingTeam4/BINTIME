import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, onSnapshot, orderBy, query, serverTimestamp, setDoc, where } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { RequestService } from "../src/requests/service";
import { SearchCandidateResolver } from "../src/requests/candidates";
import { cancelRequestCallable } from "../src/requests/callable";

// 다른 파일의 clearFirestore와 겹치지 않는 로컬 demo 프로젝트. 운영 인증/Functions/Gemini는 호출하지 않는다.
const projectId = "demo-bintime-request-candidates";
const app = initializeApp({ projectId }, `candidate-test-${randomUUID()}`);
const db = getFirestore(app);
const now = () => new Date("2026-11-11T00:00:00Z");
const service = new RequestService(db, new SearchCandidateResolver(db), now);
const cells = [0, 0, 0, 2 ** 20 + 2 ** 21 + 2 ** 22, 0, 0, 0];
const slot = { dayOfWeek: 4, startMinute: 600, endMinute: 690 };
const input = () => ({ candidateId: "receiver", clientRequestId: randomUUID() });
let env: RulesTestEnvironment;

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("BT-017 → BT-019 실제 서버 후보 어댑터", () => {
  beforeAll(async () => {
    if (!/^(127\.0\.0\.1|localhost):\d+$/.test(process.env.FIRESTORE_EMULATOR_HOST ?? "")) throw Error("local emulator only");
    env = await initializeTestEnvironment({ projectId,
      firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") } });
  });
  beforeEach(async () => {
    await env.clearFirestore();
    const batch = db.batch();
    for (const uid of ["sender", "receiver"]) {
      batch.set(db.doc(`users/${uid}`), { profileCompleted: true });
      batch.set(db.doc(`publicProfiles/${uid}`), { uid, nickname: uid });
      batch.set(db.doc(`matchSettings/${uid}`), { isMatching: true, minOverlapMinutes: 60, purposes: [] });
      batch.set(db.doc(`timetables/${uid}`), { uid, revision: 1, freeCells: cells });
      batch.set(db.doc(`availability/${uid}`), { sourceRevision: 1, slots: [slot] });
    }
    await batch.commit();
  });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("실제 후보를 재검증해 strict 스냅샷으로 생성하고 변경 후 재시도도 원본을 보존한다", async () => {
    const data = input();
    const result = await service.create("sender", data);
    expect(result).toMatchObject({ fromUid: "sender", toUid: "receiver", purpose: "전체", status: "PENDING",
      meetingDate: "2026-11-12", startMinute: 600, endMinute: 690, overlapMinutes: 90 });
    expect(result).not.toHaveProperty("candidateId");
    await db.doc("availability/sender").update({ sourceRevision: 99 });
    expect(await service.create("sender", data)).toEqual(result);
  });
  it.each(["sender", "receiver"])("%s의 스위치 OFF는 거절한다", async uid => {
    await db.doc(`matchSettings/${uid}`).update({ isMatching: false });
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
  });
  it("검색자 최소 60분이면 상대 최소 180분이어도 교집합 90분을 허용한다", async () => {
    await db.doc("matchSettings/receiver").update({ minOverlapMinutes: 180 });
    expect((await service.create("sender", input())).overlapMinutes).toBe(90);
  });
  it("검색자 최소 120분은 90분 교집합을 거절하고 90분 경계는 허용한다", async () => {
    await db.doc("matchSettings/sender").update({ minOverlapMinutes: 120 });
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
    await db.doc("matchSettings/sender").update({ minOverlapMinutes: 90 });
    expect((await service.create("sender", input())).overlapMinutes).toBe(90);
  });
  it("목적 불일치·양방향 차단·프로필 미완성을 거절한다", async () => {
    await db.doc("matchSettings/sender").update({ purposes: ["스터디"] });
    await db.doc("matchSettings/receiver").update({ purposes: ["밥"] });
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
    await db.doc("matchSettings/receiver").update({ purposes: [] });
    for (const [a, b] of [["sender", "receiver"], ["receiver", "sender"]]) {
      await db.doc(`users/${a}/blockedUsers/${b}`).set({});
      await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
      await db.doc(`users/${a}/blockedUsers/${b}`).delete();
    }
    await db.doc("users/sender").update({ profileCompleted: false });
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
  });
  it("낡은 revision·수업과 겹치는 공강·없는 공개 프로필을 거절한다", async () => {
    await db.doc("timetables/receiver").update({ revision: 2 });
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
    await db.doc("availability/receiver").update({ sourceRevision: 2 });
    await db.doc("timetables/receiver/courses/conflict").set({ timeSlots: [slot] });
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
    await db.doc("timetables/receiver/courses/conflict").delete();
    await db.doc("publicProfiles/receiver").delete();
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "INVALID_CANDIDATE" } });
  });
  it.each(["sender", "receiver"])("%s의 기존 ACTIVE 매칭과 겹치면 거절한다", async uid => {
    await db.doc("matches/existing").set({ participantUids: [uid, "third"], status: "ACTIVE",
      meetingDate: "2026-11-12", startMinute: 630, endMinute: 720 });
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "MATCH_CONFLICT" } });
    expect((await db.collection("matchRequests").get()).empty).toBe(true);
  });
  it("종료·다른 날짜·끝=시작 경계의 매칭은 충돌하지 않는다", async () => {
    await db.doc("matches/ended").set({ participantUids: ["sender"], status: "ENDED", meetingDate: "2026-11-12", startMinute: 600, endMinute: 690 });
    await db.doc("matches/other-day").set({ participantUids: ["receiver"], status: "ACTIVE", meetingDate: "2026-11-13", startMinute: 600, endMinute: 690 });
    await db.doc("matches/boundary").set({ participantUids: ["receiver"], status: "ACTIVE", meetingDate: "2026-11-12", startMinute: 690, endMinute: 720 });
    expect((await service.create("sender", input())).status).toBe("PENDING");
  });
  it("반대 방향 PENDING은 보존하고 같은 방향 중복만 거절한다(BT-031 정리 경계)", async () => {
    await service.create("sender", input());
    await expect(service.create("sender", input())).rejects.toMatchObject({ details: { reason: "DUPLICATE_PENDING" } });
    await service.create("receiver", { ...input(), candidateId: "sender" });
    expect((await db.collection("matchRequests").get()).size).toBe(2);
  });
  it("합친 Rules에서 프로필·시간표·공강·설정 권한을 보존하고 서버 전용 경로를 차단한다", async () => {
    const client = env.authenticatedContext("sender", { email: "test@school.ac.kr", email_verified: true }).firestore();
    const peer = env.authenticatedContext("receiver").firestore();
    const profile = { uid: "sender", nickname: "test", department: "software" };
    await assertSucceeds(setDoc(doc(client, "users/sender"), { ...profile, email: "test@school.ac.kr", profileCompleted: true }));
    await assertSucceeds(setDoc(doc(client, "publicProfiles/sender"), profile));
    await assertSucceeds(getDoc(doc(peer, "publicProfiles/sender")));
    await assertFails(getDoc(doc(peer, "users/sender")));
    await assertFails(setDoc(doc(client, "publicProfiles/sender"), { ...profile, email: "test@school.ac.kr" }));
    await assertSucceeds(setDoc(doc(client, "timetables/sender"), { uid: "sender", revision: 1, freeCells: cells, updatedAt: serverTimestamp() }));
    await assertSucceeds(setDoc(doc(client, "availability/sender"), { sourceRevision: 1, slots: [slot], updatedAt: serverTimestamp() }));
    await assertSucceeds(setDoc(doc(client, "matchSettings/sender"), { isMatching: true, minOverlapMinutes: 60, purposes: [], preferredTags: [], updatedAt: serverTimestamp() }));
    for (const path of ["timetables/sender", "availability/sender", "matchSettings/sender"]) await assertFails(getDoc(doc(peer, path)));
    for (const path of ["matchRequestGuards/guard", "aiUsage/sender_2026-11-12", "matches/existing"]) await assertFails(getDoc(doc(client, path)));
  });
  it("실제 후보 요청과 취소가 양측 목록에 반영되고 공강 변경 뒤에도 취소 스냅샷을 보존한다", async () => {
    const waitBoth = (status: string) => ["sender", "receiver"].map(uid => new Promise<string>((resolve, reject) => {
      const client = env.authenticatedContext(uid).firestore();
      const q = query(collection(client, "matchRequests"), where(uid === "sender" ? "fromUid" : "toUid", "==", uid), orderBy("createdAt", "desc"));
      const timer = setTimeout(() => { stop(); reject(Error("listener timeout")); }, 10_000);
      const stop = onSnapshot(q, snapshot => {
        const item = snapshot.docs.find(d => d.get("status") === status);
        if (item) { clearTimeout(timer); stop(); resolve(item.id); }
      }, error => { clearTimeout(timer); stop(); reject(error); });
    }));
    const pending = waitBoth("PENDING");
    const created = await service.create("sender", input());
    expect(await Promise.all(pending)).toEqual([created.id, created.id]);
    await db.doc("availability/sender").update({ sourceRevision: 999 });
    const canceled = waitBoth("CANCELED");
    const callable = cancelRequestCallable(() => service, () => [".ac.kr"]);
    const result = await callable.run({ auth: { uid: "sender", token: { email: "test@school.ac.kr", email_verified: true } }, data: { requestId: created.id } } as never);
    expect(await Promise.all(canceled)).toEqual([created.id, created.id]);
    expect(result).toMatchObject({ status: "CANCELED", fromUid: "sender", toUid: "receiver", purpose: created.purpose,
      meetingDate: created.meetingDate, startMinute: created.startMinute, endMinute: created.endMinute, overlapMinutes: created.overlapMinutes });
  });
});
