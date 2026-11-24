import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { parseSlots, seoulTime } from "../matching/eligibility";
import type { NotificationService } from "./service";

/** Next reminder is indexed by its due timestamp; no per-minute full-user scan or AI call. */
export class AvailabilityReminders {
  constructor(private readonly db: Firestore, private readonly notices: NotificationService,
    private readonly now: () => Date = () => new Date()) {}

  async refresh(uid: string, afterMillis = this.now().getTime()) {
    const ref = this.db.doc(`availabilityNotificationJobs/${uid}`);
    await this.db.runTransaction(async tx => {
      const [availability, timetable, user] = await Promise.all([
        tx.get(this.db.doc(`availability/${uid}`)), tx.get(this.db.doc(`timetables/${uid}`)),
        tx.get(this.db.doc(`users/${uid}`))]);
      // OFF suppresses push delivery, not creation of inbox history.
      if (!user.exists) { tx.delete(ref); return; }
      const slots = parseSlots(availability.data(), timetable.data());
      const events: { at: number; date: string; start: number }[] = [];
      for (let day = 0; day <= 7; day++) {
        const date = seoulTime(new Date(afterMillis), day);
        for (const slot of slots.filter(s => s.dayOfWeek === date.dayOfWeek)) {
          const at = Date.parse(`${date.date}T00:00:00+09:00`) + slot.startMinute * 60_000;
          if (at >= afterMillis) events.push({ at, date: date.date, start: slot.startMinute });
        }
      }
      events.sort((a, b) => a.at - b.at);
      if (!events.length) { tx.delete(ref); return; }
      const event = events[0];
      tx.set(ref, { uid, dueAt: Timestamp.fromMillis(event.at), meetingDate: event.date, startMinute: event.start,
        sourceRevision: availability.get("sourceRevision") });
    });
  }

  async runBatch() {
    const now = this.now();
    const jobs = await this.db.collection("availabilityNotificationJobs").where("dueAt", "<=", Timestamp.fromDate(now)).limit(20).get();
    for (const job of jobs.docs) {
      const due = (job.get("dueAt") as Timestamp).toMillis();
      // Inbox keeps the original start time even after an outage; push expires after two minutes.
      {
        const emitted = await this.db.runTransaction(async tx => {
          const [current, availability, timetable] = await Promise.all([tx.get(job.ref),
            tx.get(this.db.doc(`availability/${job.id}`)), tx.get(this.db.doc(`timetables/${job.id}`))]);
          if (current.get("dueAt")?.toMillis() !== due) return false;
          const day = seoulTime(new Date(due));
          return parseSlots(availability.data(), timetable.data()).some(s => s.dayOfWeek === day.dayOfWeek && s.startMinute === day.minute);
        });
        if (emitted) await this.notices.emit(job.id, `availability:${job.get("meetingDate")}:${job.get("startMinute")}`,
          { type: "availability", targetId: job.get("meetingDate") }, { occurredAt: due, pushUntil: due + 120_000 });
      }
      await this.refresh(job.id, Math.max(now.getTime(), due + 1));
    }
  }
}
