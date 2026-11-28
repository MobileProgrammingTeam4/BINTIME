import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { initializeTestEnvironment, assertFails, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { beforeAll, beforeEach, afterAll, describe, it, expect } from "vitest";
import { NotificationSettingsService, settingsInput } from "../src/notification/settings";

const projectId = "demo-bintime-settings", app = initializeApp({ projectId }, randomUUID()), db = getFirestore(app);
let env: RulesTestEnvironment, clock = Date.parse("2026-11-24T23:59:59+09:00");
const service = () => new NotificationSettingsService(db, () => new Date(clock));
describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("BT-040 user preferences and server-date popup suppression", () => {
  beforeAll(async () => {
    if (!/^(127\.0\.0\.1|localhost):/.test(process.env.FIRESTORE_EMULATOR_HOST || "")) throw new Error("local emulator required");
    env = await initializeTestEnvironment({ projectId, firestore: { rules: readFileSync("../firestore.rules", "utf8") } });
  });
  beforeEach(async () => { await env.clearFirestore(); clock = Date.parse("2026-11-24T23:59:59+09:00"); });
  afterAll(async () => { await env?.cleanup(); await db.terminate(); await deleteApp(app); });
  it("settings do not leak across accounts or reset on a fresh repository instance", async () => {
    await service().update("a", { key: "popup", value: false });
    expect((await new NotificationSettingsService(db).popupPolicy("a")).allowed).toBe(false);
    expect((await service().popupPolicy("b")).allowed).toBe(true);
    const b = env.authenticatedContext("b").firestore();
    await assertFails(getDoc(doc(b, "notificationSettings", "a")));
  });
  it("concurrent updates to different switches preserve both changes", async () => {
    await Promise.all([service().update("a", { key: "message", value: false }), service().update("a", { key: "availability", value: true })]);
    expect((await db.doc("notificationSettings/a").get()).data()).toMatchObject({ message: false, availability: true, popup: true });
  });
  it("suppress today expires at KST midnight; popup OFF still takes precedence", async () => {
    await service().update("a", { key: "suppressToday", value: true });
    expect(await service().popupPolicy("a")).toEqual({ allowed: false, today: "2026-11-24" });
    clock += 1000;
    expect(await service().popupPolicy("a")).toEqual({ allowed: true, today: "2026-11-25" });
    await service().update("a", { key: "popup", value: false });
    expect((await service().popupPolicy("a")).allowed).toBe(false);
  });
  it("turning daily suppression back off does not turn the popup switch on", async () => {
    await service().update("a", { key: "popup", value: false });
    await service().update("a", { key: "suppressToday", value: true });
    await service().update("a", { key: "suppressToday", value: false });
    expect((await service().popupPolicy("a")).allowed).toBe(false);
  });
  it("clients cannot set dates/ownership or write a preference document directly", async () => {
    expect(settingsInput.safeParse({ key: "popup", value: false, uid: "b" }).success).toBe(false);
    expect(settingsInput.safeParse({ key: "suppressedDate", value: "2099-01-01" }).success).toBe(false);
    const client = env.authenticatedContext("a").firestore();
    await assertFails(setDoc(doc(client, "notificationSettings", "a"), { popup: false }));
  });
});
