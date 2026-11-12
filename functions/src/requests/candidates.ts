import type { Firestore, Transaction } from "firebase-admin/firestore";
import { z } from "zod";
import { FirestoreMatchStore, type MatchStore, resolveCandidate } from "../matching/search";
import { type Slot, VALID_MIN_OVERLAP } from "../matching/eligibility";
import { type CandidateResolver, requestError } from "./contracts";

const slot = z.object({
  dayOfWeek: z.number().int().min(1).max(7),
  startMinute: z.number().int().min(0).max(1439),
  endMinute: z.number().int().min(1).max(1440),
}).strict().refine(s => s.startMinute < s.endMinute);
const parent = z.object({
  uid: z.string(), revision: z.number().int().min(1),
  freeCells: z.array(z.number().int().min(0).max(2 ** 48 - 1)).length(7).default([0, 0, 0, 0, 0, 0, 0]),
});
const availability = z.object({ sourceRevision: z.number().int().min(1), slots: z.array(slot).max(40) });
const courses = z.array(z.object({ timeSlots: z.array(slot).min(1).max(7) }));

function invalidCandidate() {
  return requestError("failed-precondition", "INVALID_CANDIDATE", "공강 정보가 바뀌었어요. 다시 검색해주세요.");
}

/** #88의 선택 원본·수업·revision으로 availability를 재검증한다. 실패 시 기존 요청은 변경하지 않는다. */
export function verifiedSlots(uid: string, timetable: unknown, raw: unknown, courseDocs: unknown[]): Slot[] {
  const p = parent.safeParse(timetable);
  const a = availability.safeParse(raw);
  const c = courses.safeParse(courseDocs);
  if (!p.success || !a.success || !c.success || p.data.uid !== uid || p.data.revision !== a.data.sourceRevision) {
    throw invalidCandidate();
  }
  const occupied = c.data.flatMap(course => course.timeSlots);
  const expected: Slot[] = [];
  // Number의 안전 정수 범위 안인 48비트 마스크. JS bitwise(32비트)를 사용하지 않는다.
  for (let day = 1; day <= 7; day++) {
    let start: number | null = null;
    for (let cell = 0; cell <= 48; cell++) {
      const selected = cell < 48 && Math.floor(p.data.freeCells[day - 1] / 2 ** cell) % 2 === 1;
      if (selected && occupied.some(s => s.dayOfWeek === day && s.startMinute < (cell + 1) * 30 && cell * 30 < s.endMinute)) {
        throw invalidCandidate();
      }
      if (selected && start === null) start = cell * 30;
      if (!selected && start !== null) {
        if (cell * 30 - start >= 60) expected.push({ dayOfWeek: day, startMinute: start, endMinute: cell * 30 });
        start = null;
      }
    }
  }
  const actual = [...a.data.slots].sort((x, y) => x.dayOfWeek - y.dayOfWeek || x.startMinute - y.startMinute);
  if (actual.length !== expected.length || actual.some((s, i) => s.dayOfWeek !== expected[i].dayOfWeek
      || s.startMinute !== expected[i].startMinute || s.endMinute !== expected[i].endMinute)) throw invalidCandidate();
  return actual;
}

/** A의 후보 판정 재사용 + 요청 생성에 필요한 추가 검사를 모두 같은 트랜잭션에서 수행한다. */
export class SearchCandidateResolver implements CandidateResolver {
  constructor(private readonly db: Firestore) {}

  async resolve(tx: Transaction, fromUid: string, candidateId: string, now: Date) {
    if (fromUid === candidateId) throw invalidCandidate();
    const base = new FirestoreMatchStore(this.db, ref => tx.get(ref));
    const checked: MatchStore = {
      settings: uid => base.settings(uid), blocked: (a, b) => base.blocked(a, b),
      matchingUids: limit => base.matchingUids(limit),
      slots: async uid => {
        const [p, a, c] = await Promise.all([
          tx.get(this.db.doc(`timetables/${uid}`)), tx.get(this.db.doc(`availability/${uid}`)),
          tx.get(this.db.collection(`timetables/${uid}/courses`)),
        ]);
        return verifiedSlots(uid, p.data(), a.data(), c.docs.map(d => d.data()));
      },
    };
    const candidate = await resolveCandidate(checked, fromUid, candidateId, now);
    if (!candidate) throw invalidCandidate();
    const [mine, theirs, applicant, recipient] = await Promise.all([
      checked.settings(fromUid), checked.settings(candidateId),
      tx.get(this.db.doc(`users/${fromUid}`)), tx.get(this.db.doc(`publicProfiles/${candidateId}`)),
    ]);
    // A 전달 계약: 양쪽 최소 겹침 중 큰 값. A의 현재 검색 코드가 보완되기 전에도 요청은 우회하지 않는다.
    if (!mine || !theirs || !VALID_MIN_OVERLAP.includes(mine.minOverlapMinutes as typeof VALID_MIN_OVERLAP[number])
        || !VALID_MIN_OVERLAP.includes(theirs.minOverlapMinutes as typeof VALID_MIN_OVERLAP[number])
        || candidate.overlapMinutes < Math.max(mine.minOverlapMinutes, theirs.minOverlapMinutes)
        || applicant.get("profileCompleted") !== true || !recipient.exists) throw invalidCandidate();
    for (const uid of [fromUid, candidateId]) {
      const existing = await tx.get(this.db.collection("matches")
        .where("participantUids", "array-contains", uid).where("meetingDate", "==", candidate.meetingDate));
      for (const doc of existing.docs) {
        const data = doc.data();
        if (data.status !== "ACTIVE") continue;
        if (!Number.isInteger(data.startMinute) || !Number.isInteger(data.endMinute)
            || data.startMinute < 0 || data.startMinute >= data.endMinute || data.endMinute > 1440) throw invalidCandidate();
        if (data.startMinute < candidate.endMinute && candidate.startMinute < data.endMinute) {
          throw requestError("failed-precondition", "MATCH_CONFLICT", "이미 확정된 매칭과 시간이 겹쳐요.");
        }
      }
    }
    // CandidateSnapshot은 strict: candidateId·overlapMinutes를 복사하지 않는다.
    return { toUid: candidateId, purpose: candidate.purpose, meetingDate: candidate.meetingDate,
      startMinute: candidate.startMinute, endMinute: candidate.endMinute };
  }
}
