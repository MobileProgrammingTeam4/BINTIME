import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { assertFails, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, query, setDoc, where } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { ReviewService, reviewId, type SubmitInput } from "../src/reviews/review";

/**
 * BT-044: 실제 Firestore Rules·에뮬레이터에서 후기 작성·공개 조건을 확인한다.
 * 종료된 matches 문서는 BT-038(#121) 종료 스키마(status·endReason·reviewEligible)대로 직접 넣는다.
 */
const projectId = "demo-bintime-reviews";
const app = initializeApp({ projectId }, `reviews-${randomUUID()}`);
const db = getFirestore(app);
const clock = Date.parse("2026-11-27T06:00:00Z");
const service = new ReviewService(db, () => new Date(clock));
const ended = "e".repeat(64), active = "a".repeat(64), early = "b".repeat(64), deleted = "d".repeat(64), other = "f".repeat(64);
let env: RulesTestEnvironment;

const seed = (id: string, patch: Record<string, unknown> = {}) => db.doc(`matches/${id}`).set({
  requestId: id, participantUids: ["u0", "u1"], purpose: "스터디", meetingDate: "2026-11-27", startMinute: 600, endMinute: 690,
  status: "ENDED", endReason: "AUTO", reviewEligible: true, createdAt: Timestamp.fromMillis(clock), ...patch,
});
const input = (matchId: string, patch: Partial<SubmitInput> = {}): SubmitInput =>
  ({ matchId, satisfaction: "GREAT", keywords: ["KIND_MANNER", "ON_TIME"], comment: "또 만나요", ...patch });
const reason = (r: string) => ({ code: "failed-precondition", details: expect.objectContaining({ reason: r }) });

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("BT-044 매칭 후기", () => {
  beforeAll(async () => {
    if (!/^(127\.0\.0\.1|localhost):/.test(process.env.FIRESTORE_EMULATOR_HOST ?? "")) throw new Error("local emulator required");
    env = await initializeTestEnvironment({ projectId,
      firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") } });
  });
  beforeEach(async () => { await env.clearFirestore(); });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });

  it("작성하면 reviews/{matchId}_{작성자} 에 서버가 정한 상대 UID·서버 시각으로 저장한다(BT-039 알림 필드 유지)", async () => {
    await seed(ended);
    expect(await service.submit("u0", input(ended))).toEqual({ matchId: ended, written: true });
    const saved = (await db.doc(`reviews/${reviewId(ended, "u0")}`).get()).data();
    expect(saved).toEqual({ matchId: ended, authorUid: "u0", targetUid: "u1", satisfaction: "GREAT",
      keywords: ["ON_TIME", "KIND_MANNER"], comment: "또 만나요", createdAt: Timestamp.fromMillis(clock) });
  });

  it("매칭·작성자당 1건: 두 번째는 ALREADY_REVIEWED, 동시에 보내도 1건만 남는다", async () => {
    await seed(ended);
    await service.submit("u0", input(ended));
    await expect(service.submit("u0", input(ended, { satisfaction: "BAD" }))).rejects.toMatchObject(reason("ALREADY_REVIEWED"));
    expect((await db.doc(`reviews/${reviewId(ended, "u0")}`).get()).get("satisfaction")).toBe("GREAT");

    await seed(other);
    const results = await Promise.allSettled([1, 2, 3].map(() => service.submit("u1", input(other))));
    expect(results.filter((r) => r.status === "fulfilled")).toHaveLength(1);
    for (const r of results) if (r.status === "rejected") expect(r.reason).toMatchObject(reason("ALREADY_REVIEWED"));
    expect((await db.collection("reviews").where("matchId", "==", other).get()).size).toBe(1);
  });

  it("ACTIVE·시작 전 종료·탈퇴 종료(reviewEligible=false, endReason=ACCOUNT_DELETED)·자격 필드 없음은 NOT_ELIGIBLE", async () => {
    await seed(active, { status: "ACTIVE", endReason: null, reviewEligible: null });
    await seed(early, { endReason: "MANUAL", reviewEligible: false });
    await seed(deleted, { endReason: "ACCOUNT_DELETED", reviewEligible: false });
    await seed(other);
    await db.doc(`matches/${other}`).update({ reviewEligible: FieldValue.delete() }); // BT-038 이전 ENDED(필드 없음)
    for (const id of [active, early, deleted, other]) {
      await expect(service.submit("u0", input(id))).rejects.toMatchObject(reason("NOT_ELIGIBLE"));
      expect((await service.get("u0", id)).canWrite).toBe(false);
    }
    expect((await db.collection("reviews").get()).size).toBe(0);
  });

  it("매칭 뒤 상대가 탈퇴했으면(accountDeletions/{상대}) PARTNER_DELETED, 탈퇴 중인 본인은 permission-denied", async () => {
    await seed(ended);
    await db.doc("accountDeletions/u1").set({ uid: "u1", status: "DONE" });
    await expect(service.submit("u0", input(ended))).rejects.toMatchObject(reason("PARTNER_DELETED"));
    expect((await service.get("u0", ended)).canWrite).toBe(false);
    await expect(service.submit("u1", input(ended))).rejects.toMatchObject({ code: "permission-denied" });
    expect((await db.collection("reviews").get()).size).toBe(0);
  });

  it("비참여자·없는 매칭은 작성·조회 모두 permission-denied(존재 여부를 알리지 않음)", async () => {
    await seed(ended);
    await expect(service.submit("outsider", input(ended))).rejects.toMatchObject({ code: "permission-denied" });
    await expect(service.get("outsider", ended)).rejects.toMatchObject({ code: "permission-denied" });
    await expect(service.submit("u0", input(other))).rejects.toMatchObject({ code: "permission-denied" });
    await expect(service.get("u0", other)).rejects.toMatchObject({ code: "permission-denied" });
  });

  it("조회: 내가 쓰기 전에는 상대 후기가 있어도 LOCKED(내용 없음), 쓴 뒤에는 VISIBLE, 상대가 안 썼으면 NOT_YET", async () => {
    await seed(ended);
    await service.submit("u1", input(ended, { satisfaction: "GOOD", keywords: ["CAMPUS_TIPS"], comment: null }));

    const before = await service.get("u0", ended);
    expect(before).toMatchObject({ matchId: ended, partnerUid: "u1", canWrite: true, mine: null,
      received: { visibility: "LOCKED", review: null }, meetingDate: "2026-11-27", startMinute: 600, endMinute: 690 });
    expect(JSON.stringify(before)).not.toContain("CAMPUS_TIPS");

    await service.submit("u0", input(ended));
    const after = await service.get("u0", ended);
    expect(after.canWrite).toBe(false);
    expect(after.mine).toMatchObject({ satisfaction: "GREAT", keywords: ["ON_TIME", "KIND_MANNER"], comment: "또 만나요", createdAt: clock });
    expect(after.received).toEqual({ visibility: "VISIBLE", review: { satisfaction: "GOOD", keywords: ["CAMPUS_TIPS"], comment: null, createdAt: clock } });

    await seed(other);
    await service.submit("u0", input(other));
    expect((await service.get("u0", other)).received).toEqual({ visibility: "NOT_YET", review: null });
  });

  it("앱은 reviews 를 직접 읽거나 쓸 수 없다(Rules 기본 거부): 작성자·상대·비참여자 모두", async () => {
    await seed(ended);
    await service.submit("u0", input(ended));
    await service.submit("u1", input(ended));
    for (const uid of ["u0", "u1", "outsider"]) {
      const client = env.authenticatedContext(uid, { email: `${uid}@school.ac.kr`, email_verified: true }).firestore();
      await assertFails(getDoc(doc(client, "reviews", reviewId(ended, "u0"))));
      await assertFails(getDoc(doc(client, "reviews", reviewId(ended, "u1"))));
      await assertFails(getDocs(query(collection(client, "reviews"), where("matchId", "==", ended))));
      await assertFails(setDoc(doc(client, "reviews", reviewId(other, uid)), { matchId: other, authorUid: uid, targetUid: "x", satisfaction: "GREAT", keywords: [], comment: null }));
    }
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "reviews", reviewId(ended, "u0"))));
  });
});
