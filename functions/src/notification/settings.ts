import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { z } from "zod";
import { DEFAULT_SETTINGS, KINDS } from "./service";
import { seoulTime } from "../matching/eligibility";

export const settingsInput = z.object({ key: z.enum([...KINDS, "popup", "suppressToday"]), value: z.boolean() }).strict();
export class NotificationSettingsService {
  constructor(private readonly db: Firestore, private readonly now: () => Date = () => new Date()) {}
  async update(uid: string, input: z.infer<typeof settingsInput>) {
    const parsed = settingsInput.parse(input), ref = this.db.doc(`notificationSettings/${uid}`);
    await this.db.runTransaction(async tx => {
      const old = await tx.get(ref);
      const now = this.now();
      const patch = parsed.key === "suppressToday" ? { suppressedDate: parsed.value ? seoulTime(now).date : "" } : { [parsed.key]: parsed.value };
      tx.set(ref, { ...(old.exists ? {} : DEFAULT_SETTINGS), ...patch, updatedAt: Timestamp.fromDate(now) }, { merge: true });
    });
    return { saved: true };
  }
  async popupPolicy(uid: string) {
    const settings = await this.db.doc(`notificationSettings/${uid}`).get(), today = seoulTime(this.now()).date;
    return { allowed: (settings.get("popup") ?? DEFAULT_SETTINGS.popup) === true && settings.get("suppressedDate") !== today, today };
  }
}
