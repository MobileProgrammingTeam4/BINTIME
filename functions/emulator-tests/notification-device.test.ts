import { initializeApp, deleteApp, type App } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { beforeAll, beforeEach, afterAll, describe, expect, it } from "vitest";
import { registerNotificationDevice } from "../src/notification/functions";
import { eventKey } from "../src/notification/service";

const token = "emulator-device-token-123";
const call = (uid: string, remove = false) => registerNotificationDevice.run({
  auth: { uid, token: { email: `${uid}@school.ac.kr`, email_verified: true } }, data: { token, remove },
} as never);

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("BT-039 authenticated logout device removal", () => {
  let app: App, db: Firestore, oldSuffixes: string | undefined;
  beforeAll(() => {
    if (!/^(127\.0\.0\.1|localhost):/.test(process.env.FIRESTORE_EMULATOR_HOST || "")) throw new Error("local emulator required");
    app = initializeApp({ projectId: "demo-bintime-notification-device" }); db = getFirestore(app);
    oldSuffixes = process.env.ALLOWED_EMAIL_SUFFIXES; process.env.ALLOWED_EMAIL_SUFFIXES = ".ac.kr";
  });
  beforeEach(async () => { await db.doc(`notificationDevices/${eventKey(token)}`).delete(); });
  afterAll(async () => {
    if (oldSuffixes === undefined) delete process.env.ALLOWED_EMAIL_SUFFIXES;
    else process.env.ALLOWED_EMAIL_SUFFIXES = oldSuffixes;
    await db.terminate(); await deleteApp(app);
  });
  it("removes only this device and permits repeated logout cleanup", async () => {
    await call("a");
    expect((await db.doc(`notificationDevices/${eventKey(token)}`).get()).get("uid")).toBe("a");
    await call("a", true); await call("a", true);
    expect((await db.doc(`notificationDevices/${eventKey(token)}`).get()).exists).toBe(false);
  });
  it("an old account's delayed removal preserves a token rebound to another account", async () => {
    await call("a"); await call("b"); await call("a", true);
    expect((await db.doc(`notificationDevices/${eventKey(token)}`).get()).get("uid")).toBe("b");
  });
  it("refuses anonymous cleanup and client supplied UIDs", async () => {
    await expect(registerNotificationDevice.run({ data: { token, remove: true } } as never)).rejects.toMatchObject({ code: "unauthenticated" });
    await expect(registerNotificationDevice.run({ auth: { uid: "a", token: { email: "a@school.ac.kr", email_verified: true } },
      data: { token, remove: true, uid: "b" } } as never)).rejects.toMatchObject({ code: "invalid-argument" });
  });
});
