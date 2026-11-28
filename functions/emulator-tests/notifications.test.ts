import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { initializeTestEnvironment, assertFails, assertSucceeds, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, serverTimestamp } from "firebase/firestore";
import { beforeAll, afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationService } from "../src/notification/service";
import { NotificationEvents } from "../src/notification/events";
import { AvailabilityReminders } from "../src/notification/availability";

const projectId = "demo-bintime-notifications", app = initializeApp({ projectId }, randomUUID()), db = getFirestore(app);
let env: RulesTestEnvironment, clock = Date.parse("2026-11-24T09:59:00+09:00");
const send = vi.fn(async () => ({ responses: [{ success: true }], successCount: 1, failureCount: 0 }));
const service = () => new NotificationService(db, { sendEachForMulticast: send } as never, () => new Date(clock));
const reminders = () => new AvailabilityReminders(db, service(), () => new Date(clock));
describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("BT-039 inbox, routing, FCM outbox and free-slot reminders", () => {
  beforeAll(async () => {
    if (!/^(127\.0\.0\.1|localhost):/.test(process.env.FIRESTORE_EMULATOR_HOST || "")) throw new Error("local emulator required");
    env = await initializeTestEnvironment({ projectId, firestore: { rules: readFileSync("../firestore.rules", "utf8") } });
  });
  beforeEach(async () => { await env.clearFirestore(); send.mockReset(); send.mockResolvedValue({ responses: [{ success: true }], successCount: 1, failureCount: 0 });
    clock = Date.parse("2026-11-24T09:59:00+09:00"); await db.doc("users/a").set({ nickname: "테스트" }); await db.doc("users/b").set({ nickname: "상대" }); });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });
  it("an event creates one owner's inbox item and one push job even concurrently", async () => {
    const ids = await Promise.all([service().emit("a", "one", { type: "message", targetId: "room" }), service().emit("a", "one", { type: "message", targetId: "room" })]);
    expect(ids[0]).toBe(ids[1]); expect((await db.collection("notifications/a/items").get()).size).toBe(1);
    expect((await db.collection("notificationPushJobs").get()).size).toBe(1);
    await service().emit("deleted", "one", { type: "message", targetId: "room" });
    expect((await db.collection("notifications/deleted/items").get()).empty).toBe(true);
  });
  it("owner can mark read, but cannot modify targets or see other inbox/devices/jobs", async () => {
    const id = await service().emit("a", "one", { type: "message", targetId: "room" });
    const owner = env.authenticatedContext("a").firestore(), outsider = env.authenticatedContext("b").firestore();
    await assertSucceeds(getDoc(doc(owner, "notifications/a/items", id)));
    await assertSucceeds(updateDoc(doc(owner, "notifications/a/items", id), { readAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(owner, "notifications/a/items", id), { targetId: "other" }));
    await assertFails(getDoc(doc(outsider, "notifications/a/items", id)));
    await assertFails(setDoc(doc(owner, "notificationDevices", "x"), { token: "secret" }));
    await assertFails(getDoc(doc(owner, "notificationPushJobs", id)));
  });
  it("push OFF still preserves inbox history", async () => {
    await db.doc("notificationSettings/a").set({ message: false });
    const id = await service().emit("a", "one", { type: "message", targetId: "room" }); await service().deliver(id);
    expect(send).not.toHaveBeenCalled(); expect((await db.doc(`notifications/a/items/${id}`).get()).exists).toBe(true);
  });
  it("only recipient's registered devices receive a generic UID-bound payload", async () => {
    await db.doc("notificationDevices/first").set({ uid: "a", token: "token_a" });
    await db.doc("notificationDevices/second").set({ uid: "b", token: "token_b" });
    const id = await service().emit("a", "one", { type: "message", targetId: "room", actorUid: "b" });
    await Promise.all([service().deliver(id), service().deliver(id)]);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0][0]).toMatchObject({ tokens: ["token_a"], data: { recipientUid: "a", notificationId: id } });
    expect(JSON.stringify(send.mock.calls[0][0])).not.toContain("actorUid");
  });
  it("transient FCM failures stop after three attempts; inbox is not rolled back", async () => {
    await db.doc("notificationDevices/first").set({ uid: "a", token: "token_a" }); send.mockRejectedValue(new Error("offline"));
    const id = await service().emit("a", "one", { type: "message", targetId: "room" });
    for (let n = 0; n < 4; n++) { await service().deliver(id); clock += 180_000; }
    expect(send).toHaveBeenCalledTimes(3); expect((await db.doc(`notificationPushJobs/${id}`).get()).get("status")).toBe("FAILED");
    expect((await db.doc(`notifications/a/items/${id}`).get()).exists).toBe(true);
  });
  it("early-ended appointments do not produce review notifications and invalid review authors are rejected", async () => {
    const events = new NotificationEvents(db, service());
    await events.matchChanged("match", { status: "ACTIVE" }, { status: "ENDED", reviewEligible: false, participantUids: ["a", "b"] });
    expect((await db.collection("notifications/a/items").get()).empty).toBe(true);
    await db.doc("matches/match").set({ status: "ENDED", reviewEligible: true, participantUids: ["a", "b"] });
    await events.reviewCreated("review", { matchId: "match", authorUid: "outsider", targetUid: "a" });
    expect((await db.collection("notifications/a/items").get()).empty).toBe(true);
    await events.reviewCreated("review", { matchId: "match", authorUid: "b", targetUid: "a", comment: "PRIVATE" });
    const notices = await db.collection("notifications/a/items").get(); expect(notices.size).toBe(1);
    expect(JSON.stringify(notices.docs[0].data())).not.toContain("PRIVATE");
  });
  async function seedAvailability() {
    await db.doc("availability/a").set({ sourceRevision: 1, slots: [{ dayOfWeek: 2, startMinute: 600, endMinute: 690 }] });
    await db.doc("timetables/a").set({ revision: 1 });
    await db.doc("notificationSettings/a").set({ availability: true });
    await db.doc("matchSettings/a").set({ isMatching: false });
  }
  it("free-slot reminder occurs at its start even with matching OFF, not earlier or twice", async () => {
    await seedAvailability(); await reminders().refresh("a"); await reminders().runBatch();
    expect((await db.collection("notifications/a/items").get()).empty).toBe(true);
    clock += 60_000; await reminders().runBatch(); await reminders().runBatch();
    expect((await db.collection("notifications/a/items").get()).size).toBe(1);
    expect((await db.doc("availabilityNotificationJobs/a").get()).get("dueAt").toMillis()).toBeGreaterThan(clock);
  });
  it("stale revisions suppress the reminder", async () => {
    await seedAvailability(); await reminders().refresh("a"); await db.doc("timetables/a").update({ revision: 2 });
    clock += 60_000; await reminders().runBatch();
    expect((await db.collection("notifications/a/items").get()).empty).toBe(true);
    expect((await db.doc("availabilityNotificationJobs/a").get()).exists).toBe(false);
  });
  it("settings/profile refresh after the slot start preserves the unprocessed reminder", async () => {
    await seedAvailability(); await reminders().refresh("a");
    clock += 90_000;
    await db.doc("notificationSettings/a").update({ message: false });
    await reminders().refresh("a");
    expect((await db.doc("availabilityNotificationJobs/a").get()).get("dueAt").toMillis()).toBe(Date.parse("2026-11-24T10:00:00+09:00"));
    await reminders().runBatch();
    expect((await db.collection("notifications/a/items").get()).size).toBe(1);
    expect((await db.doc("availabilityNotificationJobs/a").get()).get("dueAt").toMillis()).toBeGreaterThan(clock);
  });
  it("a changed slot replaces the pending reminder and a stale worker cannot advance it", async () => {
    await seedAvailability(); await reminders().refresh("a");
    const oldDue = (await db.doc("availabilityNotificationJobs/a").get()).get("dueAt").toMillis();
    clock += 90_000;
    await db.doc("availability/a").update({ slots: [{ dayOfWeek: 2, startMinute: 630, endMinute: 720 }] });
    await reminders().refresh("a");
    const replacement = (await db.doc("availabilityNotificationJobs/a").get()).get("dueAt").toMillis();
    expect(replacement).toBe(Date.parse("2026-11-24T10:30:00+09:00"));
    await reminders().refresh("a", replacement + 1, oldDue);
    expect((await db.doc("availabilityNotificationJobs/a").get()).get("dueAt").toMillis()).toBe(replacement);
    await reminders().runBatch(); expect((await db.collection("notifications/a/items").get()).empty).toBe(true);
  });
  it("free-slot push OFF still creates inbox history", async () => {
    await seedAvailability(); await db.doc("notificationSettings/a").update({ availability: false });
    await reminders().refresh("a"); clock += 60_000; await reminders().runBatch(); await service().retryBatch();
    expect((await db.collection("notifications/a/items").get()).size).toBe(1); expect(send).not.toHaveBeenCalled();
  });
  it("an outage preserves the original inbox time but skips the expired push", async () => {
    await seedAvailability(); await reminders().refresh("a"); clock += 10 * 60_000; await reminders().runBatch();
    const notices = await db.collection("notifications/a/items").get(); expect(notices.size).toBe(1);
    expect(notices.docs[0].get("createdAt").toMillis()).toBe(Date.parse("2026-11-24T10:00:00+09:00"));
    await service().retryBatch(); expect(send).not.toHaveBeenCalled();
    expect((await db.doc("availabilityNotificationJobs/a").get()).get("dueAt")).toBeInstanceOf(Timestamp);
  });
  it("a failed free-slot push expires during retries at the same original deadline", async () => {
    await seedAvailability(); await db.doc("notificationDevices/first").set({ uid: "a", token: "token_a" });
    await reminders().refresh("a"); clock += 60_000; await reminders().runBatch();
    send.mockRejectedValueOnce(new Error("temporary")); await service().retryBatch();
    clock += 120_001; await service().retryBatch(); expect(send).toHaveBeenCalledTimes(1);
    expect((await db.collection("notifications/a/items").get()).size).toBe(1);
  });
});
