import { createHash } from "node:crypto";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import type { Messaging, MulticastMessage } from "firebase-admin/messaging";

export const KINDS = ["message", "request", "accepted", "availability", "reviewWrite", "reviewReceived"] as const;
export type NotificationKind = typeof KINDS[number];
export const DEFAULT_SETTINGS = { message: true, request: true, accepted: false, availability: false,
  reviewWrite: false, reviewReceived: false, popup: true, suppressedDate: "" };
export const eventKey = (source: string) => createHash("sha256").update(source).digest("hex");
export interface Notice { type: NotificationKind; targetId: string; actorUid?: string; matchId?: string; requestId?: string }
export const noticeTitle: Record<NotificationKind, string> = { message: "새 메시지가 도착했어요", request: "새 매칭 요청이 도착했어요",
  accepted: "매칭이 성사되었어요", availability: "공강이 시작되었어요", reviewWrite: "매칭 후기를 남겨주세요", reviewReceived: "상대방이 후기를 남겼어요" };

/** Backend-owned inbox + bounded outbox. FCM is never inside a business-state transaction. */
export class NotificationService {
  constructor(private readonly db: Firestore, private readonly messaging: Pick<Messaging, "sendEachForMulticast">,
    private readonly now: () => Date = () => new Date()) {}

  async emit(uid: string, source: string, notice: Notice, delivery?: { occurredAt: number; pushUntil: number }) {
    const id = eventKey(`${uid}:${source}`), ref = this.db.doc(`notifications/${uid}/items/${id}`);
    await this.db.runTransaction(async tx => {
      const [old, user] = await Promise.all([tx.get(ref), tx.get(this.db.doc(`users/${uid}`))]);
      if (old.exists || !user.exists) return;
      tx.create(ref, { ...notice, title: noticeTitle[notice.type], createdAt: delivery ? Timestamp.fromMillis(delivery.occurredAt) : Timestamp.fromDate(this.now()), readAt: null });
      tx.create(this.db.doc(`notificationPushJobs/${id}`), { uid, notificationId: id, status: "PENDING", attempts: 0,
        nextAttemptAt: Timestamp.fromDate(this.now()), createdAt: Timestamp.fromDate(this.now()),
        ...(delivery ? { expiresAt: Timestamp.fromMillis(delivery.pushUntil) } : {}) });
    });
    return id;
  }

  async deliver(jobId: string) {
    const ref = this.db.doc(`notificationPushJobs/${jobId}`);
    const claim = await this.db.runTransaction(async tx => {
      const job = await tx.get(ref);
      if (!job.exists || !["PENDING", "SENDING"].includes(job.get("status"))) return null;
      const now = this.now(), next = job.get("nextAttemptAt");
      const expiresAt = job.get("expiresAt");
      if (expiresAt instanceof Timestamp && now.getTime() > expiresAt.toMillis()) { tx.update(ref, { status: "SKIPPED", nextAttemptAt: null }); return null; }
      if (next instanceof Timestamp && next.toMillis() > now.getTime()) return null;
      const attempts = Number(job.get("attempts") || 0);
      if (attempts >= 3) { tx.update(ref, { status: "FAILED", nextAttemptAt: null }); return null; }
      tx.update(ref, { status: "SENDING", attempts: attempts + 1, nextAttemptAt: Timestamp.fromMillis(now.getTime() + 120_000) });
      return { uid: job.get("uid") as string, id: job.get("notificationId") as string,
        expiresAt: expiresAt instanceof Timestamp ? expiresAt.toMillis() : null };
    });
    if (!claim) return;
    try {
      const [notice, preferences, devices] = await Promise.all([this.db.doc(`notifications/${claim.uid}/items/${claim.id}`).get(),
        this.db.doc(`notificationSettings/${claim.uid}`).get(), this.db.collection("notificationDevices").where("uid", "==", claim.uid).limit(10).get()]);
      const type = notice.get("type") as NotificationKind;
      if (!notice.exists || !KINDS.includes(type)) { await ref.update({ status: "SKIPPED", nextAttemptAt: null }); return; }
      const enabled = preferences.get(type) ?? DEFAULT_SETTINGS[type];
      if (!notice.exists || !KINDS.includes(type) || !enabled || notice.get("readAt") != null || devices.empty
          || (claim.expiresAt !== null && this.now().getTime() > claim.expiresAt)) {
        await ref.update({ status: "SKIPPED", nextAttemptAt: null }); return;
      }
      const message: MulticastMessage = { tokens: devices.docs.map(d => d.get("token")), data: { recipientUid: claim.uid,
        notificationId: claim.id, type, targetId: notice.get("targetId") || "", title: noticeTitle[type] },
        android: { priority: "high", ttl: claim.expiresAt === null ? 300_000 : Math.max(0, Math.min(300_000, claim.expiresAt - this.now().getTime())), collapseKey: claim.id } };
      // Generic data-only payload: the client checks current UID before displaying anything.
      const response = await this.messaging.sendEachForMulticast(message);
      let transient = false;
      for (let i = 0; i < response.responses.length; i++) {
        const error = response.responses[i].error;
        if (!error) continue;
        if (["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(error.code)) {
          // Do not delete a token that was rebound to another account while sending.
          await this.db.runTransaction(async tx => { const current = await tx.get(devices.docs[i].ref);
            if (current.get("uid") === claim.uid && current.get("token") === message.tokens[i]) tx.delete(current.ref); });
        } else transient = true;
      }
      await ref.update({ status: transient ? "PENDING" : "SENT", nextAttemptAt: transient ? Timestamp.fromMillis(this.now().getTime() + 60_000) : null });
    } catch {
      await ref.update({ status: "PENDING", nextAttemptAt: Timestamp.fromMillis(this.now().getTime() + 60_000) });
    }
  }

  async retryBatch() {
    const jobs = await this.db.collection("notificationPushJobs").where("status", "in", ["PENDING", "SENDING"])
      .where("nextAttemptAt", "<=", Timestamp.fromDate(this.now())).limit(20).get();
    for (const job of jobs.docs) await this.deliver(job.id);
  }
}
