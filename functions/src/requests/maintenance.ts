import { Timestamp, type DocumentSnapshot, type Firestore, type Transaction } from "firebase-admin/firestore";
import { endsAt, overlaps } from "./policy";
import type { RequestRecord } from "./service";

export const CONFLICT_PAGE_SIZE = 40;
export const EXPIRY_PAGE_SIZE = 25;
export interface ConflictPlan {
  rows: DocumentSnapshot[];
  guards: DocumentSnapshot[];
  overflow: boolean;
}

/** 수락과 같은 트랜잭션의 읽기 단계. 쓰기를 시작하기 전에 제3자 잠금까지 읽는다. */
export async function readConflicts(db: Firestore, tx: Transaction, matchId: string,
  participants: string[], time: RequestRecord, acceptedAt: Timestamp): Promise<ConflictPlan> {
  const queries = participants.flatMap(uid => (["fromUid", "toUid"] as const).map(direction => db.collection("matchRequests")
    .where(direction, "==", uid).where("status", "==", "PENDING").where("meetingDate", "==", time.meetingDate)
    .where("createdAt", "<=", acceptedAt).where("startMinute", "<", time.endMinute).where("endMinute", ">", time.startMinute)
    .orderBy("createdAt").orderBy("startMinute").orderBy("endMinute").limit(CONFLICT_PAGE_SIZE)));
  const pages = await Promise.all(queries.map(q => tx.get(q)));
  const rows = [...new Map(pages.flatMap(p => p.docs).filter(d => d.id !== matchId).map(d => [d.id, d])).values()];
  const others = [...new Set(rows.flatMap(d => [d.get("fromUid"), d.get("toUid")]))].filter(uid => !participants.includes(uid));
  if (others.some(uid => typeof uid !== "string" || !uid)) throw new Error("invalid cleanup participant");
  const guards = await Promise.all(others.map(uid => tx.get(db.doc(`requestUserGuards/${uid}`))));
  return { rows, guards, overflow: pages.some(p => p.size === CONFLICT_PAGE_SIZE) };
}

export function touchGuard(tx: Transaction, snapshot: DocumentSnapshot) {
  const revision = snapshot.get("revision");
  tx.set(snapshot.ref, { revision: Number.isSafeInteger(revision) ? revision + 1 : 1 }, { merge: true });
}

export function writeConflicts(tx: Transaction, plan: ConflictPlan, participants: string[], time: RequestRecord, now: Date) {
  for (const doc of plan.rows) {
    const row = doc.data() as RequestRecord;
    if (row.status !== "PENDING" || !overlaps(row, time)) continue;
    const expired = endsAt(row) <= now.getTime();
    const sent = participants.includes(row.fromUid);
    tx.update(doc.ref, { status: expired || sent ? "CANCELED" : "REJECTED",
      terminalReason: expired ? "EXPIRED" : "MATCH_CONFLICT", updatedAt: Timestamp.fromDate(now) });
  }
  for (const guard of plan.guards) touchGuard(tx, guard);
}

/** 운영 예약과 분리한 서버 내부 처리기. 테스트는 demo 로컬 Firestore만 주입한다. */
export class RequestMaintenance {
  constructor(private readonly db: Firestore, private readonly now: () => Date = () => new Date()) {}

  async expire(requestId: string): Promise<boolean> {
    return this.db.runTransaction(async tx => {
      const doc = await tx.get(this.db.doc(`matchRequests/${requestId}`));
      if (!doc.exists || doc.get("status") !== "PENDING") return false;
      const row = doc.data() as RequestRecord;
      const now = this.now();
      if (endsAt(row) > now.getTime()) return false;
      const guards = await Promise.all([row.fromUid, row.toUid].map(uid => tx.get(this.db.doc(`requestUserGuards/${uid}`))));
      tx.update(doc.ref, { status: "CANCELED", terminalReason: "EXPIRED", updatedAt: Timestamp.fromDate(now) });
      guards.forEach(g => touchGuard(tx, g));
      return true;
    });
  }

  async cleanup(matchId: string) {
    return this.db.runTransaction(async tx => {
      const match = await tx.get(this.db.doc(`matches/${matchId}`));
      if (!match.exists) throw new Error("cleanup match missing");
      const time = match.data() as RequestRecord;
      const participants: string[] = match.get("participantUids");
      const acceptedAt: Timestamp = match.get("createdAt");
      if (!Array.isArray(participants) || participants.length !== 2 || !(acceptedAt instanceof Timestamp)) throw new Error("invalid cleanup match");
      const guards = await Promise.all(participants.map(uid => tx.get(this.db.doc(`requestUserGuards/${uid}`))));
      const plan = await readConflicts(this.db, tx, matchId, participants, time, acceptedAt);
      const now = this.now();
      writeConflicts(tx, plan, participants, time, now);
      guards.forEach(g => touchGuard(tx, g));
      tx.set(this.db.doc(`matchCleanupJobs/${matchId}`), { matchId, status: plan.overflow ? "PENDING" : "DONE",
        attempts: 0, acceptedAt, updatedAt: Timestamp.fromDate(now) }, { merge: true });
      return plan.rows.length;
    });
  }

  async runBatch() {
    const now = this.now();
    const seoul = new Date(now.getTime() + 9 * 3_600_000);
    const date = seoul.toISOString().slice(0, 10);
    const minute = seoul.getUTCHours() * 60 + seoul.getUTCMinutes() + seoul.getUTCSeconds() / 60;
    const base = this.db.collection("matchRequests").where("status", "==", "PENDING");
    const [past, today, jobs] = await Promise.all([
      base.where("meetingDate", "<", date).orderBy("meetingDate").limit(EXPIRY_PAGE_SIZE).get(),
      base.where("meetingDate", "==", date).where("endMinute", "<=", minute).orderBy("endMinute").limit(EXPIRY_PAGE_SIZE).get(),
      this.db.collection("matchCleanupJobs").where("status", "==", "PENDING").limit(2).get(),
    ]);
    let expired = 0, cleaned = 0, failed = 0;
    for (const doc of [...past.docs, ...today.docs]) {
      try { if (await this.expire(doc.id)) expired++; }
      catch { failed++; } // 원본을 건드리지 않고 다음 항목을 진행. 함수 결과에서 실패 수를 기록한다.
    }
    for (const job of jobs.docs) {
      try { cleaned += await this.cleanup(job.id); }
      catch {
        failed++;
        await this.db.runTransaction(async tx => {
          const fresh = await tx.get(job.ref);
          if (fresh.get("status") !== "PENDING") return;
          const attempts = (Number(fresh.get("attempts")) || 0) + 1;
          tx.update(job.ref, { attempts, status: attempts >= 3 ? "FAILED" : "PENDING", updatedAt: Timestamp.fromDate(this.now()) });
        });
      }
    }
    return { expired, cleaned, failed };
  }
}
