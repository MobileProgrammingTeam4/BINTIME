import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getDatabase } from "firebase-admin/database";
import { onDocumentCreated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { onValueCreated } from "firebase-functions/v2/database";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { z } from "zod";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { chatTriggerRegion } from "../chat/trigger";
import { NotificationService, eventKey } from "./service";
import { NotificationEvents } from "./events";
import { AvailabilityReminders } from "./availability";
import { NotificationSettingsService, settingsInput } from "./settings";

const options = { region: "asia-northeast3", timeoutSeconds: 60, maxInstances: 2, retry: true };
const fresh = (time: string) => Number.isFinite(Date.parse(time)) && Date.now() - Date.parse(time) <= 3_600_000;
export function notificationServices() {
  const app = getApps().find(a => a.name === "[DEFAULT]") ?? initializeApp();
  const db = getFirestore(app), notices = new NotificationService(db, getMessaging(app));
  return { db, notices, events: new NotificationEvents(db, notices), reminders: new AvailabilityReminders(db, notices), database: () => getDatabase(app) };
}
export const notificationOnRequestCreated = onDocumentCreated({ ...options, document: "matchRequests/{id}" }, async e => {
  if (e.data && fresh(e.time)) await notificationServices().events.requestCreated(e.params.id, e.data.data());
});
export const notificationOnMatchChanged = onDocumentWritten({ ...options, document: "matches/{id}" }, async e => {
  if (e.data?.after.exists && fresh(e.time)) await notificationServices().events.matchChanged(e.params.id, e.data.before.data(), e.data.after.data()!);
});
export const notificationOnReviewCreated = onDocumentCreated({ ...options, document: "reviews/{id}" }, async e => {
  if (e.data && fresh(e.time)) await notificationServices().events.reviewCreated(e.params.id, e.data.data());
});
export const notificationOnMessageCreated = onValueCreated({ ref: "/chatRooms/{roomId}/messages/{messageId}",
  region: chatTriggerRegion(), maxInstances: 2, timeoutSeconds: 30, retry: true }, async e => {
  if (Date.now() - Date.parse(e.time) > 300_000) return;
  const services = notificationServices();
  await services.events.messageCreated(services.database(), e.params.roomId, e.params.messageId, e.data.val());
});
export const notificationOnPushQueued = onDocumentCreated({ ...options, document: "notificationPushJobs/{id}" }, async e => {
  if (!fresh(e.time)) return;
  await notificationServices().notices.deliver(e.params.id);
});
const refresh = async (uid: string) => notificationServices().reminders.refresh(uid);
export const notificationOnAvailabilityChanged = onDocumentWritten({ ...options, document: "availability/{uid}" }, e => refresh(e.params.uid));
export const notificationOnTimetableChanged = onDocumentWritten({ ...options, document: "timetables/{uid}" }, e => refresh(e.params.uid));
export const notificationOnSettingsChanged = onDocumentWritten({ ...options, document: "notificationSettings/{uid}" }, e => refresh(e.params.uid));
export const notificationOnUserChanged = onDocumentWritten({ ...options, document: "users/{uid}" }, e => refresh(e.params.uid));
/** Deployment and cost approval are separate from this implementation. */
export const maintainNotifications = onSchedule({ schedule: "every 1 minutes", timeZone: "Asia/Seoul", region: "asia-northeast3",
  timeoutSeconds: 60, maxInstances: 1, minInstances: 0, retryCount: 0 }, async () => {
  const { notices, reminders } = notificationServices(); await reminders.runBatch(); await notices.retryBatch();
});

const deviceInput = z.object({ token: z.string().min(10).max(4096).regex(/^[A-Za-z0-9:_.-]+$/), remove: z.boolean().default(false) }).strict();
export const registerNotificationDevice = onCall({ region: "asia-northeast3", maxInstances: 2 }, async request => {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
  if (checkSchoolEmail(request.auth.token, parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]))) throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.");
  const parsed = deviceInput.safeParse(request.data);
  if (!parsed.success) throw new HttpsError("invalid-argument", "알림 기기를 등록하지 못했어요.");
  const { db } = notificationServices(), ref = db.doc(`notificationDevices/${eventKey(parsed.data.token)}`), uid = request.auth.uid;
  await db.runTransaction(async tx => {
    const old = await tx.get(ref);
    if (parsed.data.remove) { if (old.get("uid") === uid) tx.delete(ref); }
    else tx.set(ref, { uid, token: parsed.data.token, updatedAt: Timestamp.now() });
  });
  return { registered: !parsed.data.remove };
});

export const updateNotificationSettings = onCall({ region: "asia-northeast3", maxInstances: 2 }, async request => {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
  if (checkSchoolEmail(request.auth.token, parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]))) throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.");
  const parsed = settingsInput.safeParse(request.data);
  if (!parsed.success) throw new HttpsError("invalid-argument", "알림 설정을 확인해주세요.");
  return new NotificationSettingsService(notificationServices().db).update(request.auth.uid, parsed.data);
});
export const requestPopupPolicy = onCall({ region: "asia-northeast3", maxInstances: 2 }, async request => {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
  if (checkSchoolEmail(request.auth.token, parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]))) throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.");
  if (!z.object({}).strict().safeParse(request.data ?? {}).success) throw new HttpsError("invalid-argument", "요청 정보를 확인해주세요.");
  return new NotificationSettingsService(notificationServices().db).popupPolicy(request.auth.uid);
});
