import type { Firestore } from "firebase-admin/firestore";
import type { Database } from "firebase-admin/database";
import type { NotificationService } from "./service";
import { endsAt } from "../requests/policy";

export class NotificationEvents {
  constructor(private readonly db: Firestore, private readonly notices: NotificationService) {}
  async requestCreated(id: string, data: Record<string, unknown>) {
    if (data.status !== "PENDING" || typeof data.fromUid !== "string" || typeof data.toUid !== "string" || data.fromUid === data.toUid) return;
    // A delayed create event must not reopen an already processed request popup.
    const latest = await this.db.doc(`matchRequests/${id}`).get();
    if (latest.get("status") !== "PENDING") return;
    try { if (endsAt(latest.data() as { meetingDate: string; endMinute: number }) <= Date.now()) return; } catch { return; }
    await this.notices.emit(data.toUid, `request:${id}`, { type: "request", targetId: id, requestId: id, actorUid: data.fromUid });
  }
  async matchChanged(id: string, before: Record<string, unknown> | undefined, data: Record<string, unknown>) {
    const participants = data.participantUids;
    if (!Array.isArray(participants) || participants.length !== 2 || participants.some(u => typeof u !== "string")) return;
    if (!before) {
      const request = await this.db.doc(`matchRequests/${id}`).get(), sender = request.get("fromUid");
      if (participants.includes(sender)) await this.notices.emit(sender, `accepted:${id}`, { type: "accepted", targetId: id, matchId: id });
    }
    if (data.status === "ENDED" && before?.status !== "ENDED" && data.reviewEligible === true) {
      for (const uid of participants) await this.notices.emit(uid, `reviewWrite:${id}`, { type: "reviewWrite", targetId: id, matchId: id });
    }
  }
  async reviewCreated(id: string, review: Record<string, unknown>) {
    const { matchId, authorUid, targetUid } = review;
    if (typeof matchId !== "string" || typeof authorUid !== "string" || typeof targetUid !== "string" || authorUid === targetUid) return;
    const match = await this.db.doc(`matches/${matchId}`).get(), participants = match.get("participantUids");
    if (match.get("status") !== "ENDED" || match.get("reviewEligible") !== true
        || !Array.isArray(participants) || ![authorUid, targetUid].every(u => participants.includes(u))) return;
    // Deliberately omit review text/ratings: BT-044 controls conditional disclosure.
    await this.notices.emit(targetUid, `reviewReceived:${matchId}:${authorUid}`, { type: "reviewReceived", targetId: matchId, matchId, actorUid: authorUid });
  }
  async messageCreated(database: Database, roomId: string, messageId: string, value: Record<string, unknown>) {
    if (typeof value.senderId !== "string" || typeof value.content !== "string" || !value.content.trim()) return;
    const [members, control] = await Promise.all([database.ref(`chatRooms/${roomId}/members`).get(), database.ref(`chatRooms/${roomId}/control`).get()]);
    const ids = Object.keys(members.val() || {});
    if (ids.length !== 2 || !ids.includes(value.senderId) || control.val()?.archived === true) return;
    const receiver = ids.find(u => u !== value.senderId)!;
    const entry = (await database.ref(`userChatRooms/${receiver}/${roomId}`).get()).val();
    if (!entry || entry.hidden === true || entry.archived === true) return;
    await this.notices.emit(receiver, `message:${roomId}:${messageId}`, { type: "message", targetId: roomId, actorUid: value.senderId });
  }
}
