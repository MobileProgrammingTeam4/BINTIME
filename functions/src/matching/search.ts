import type { DocumentReference, DocumentSnapshot, Firestore } from "firebase-admin/firestore";
import {
  type Candidate,
  type MatchSettings,
  type Slot,
  type Window,
  evaluate,
  parseSettings,
  parseSlots,
  referenceWindow,
} from "./eligibility";

/** 검색·요청 재검증이 읽는 데이터. 테스트는 메모리 구현, D 의 요청 트랜잭션은 tx.get 구현을 쓴다. */
export interface MatchStore {
  settings(uid: string): Promise<MatchSettings | null>;
  slots(uid: string): Promise<Slot[]>;
  /** a 가 b 를 차단했는지 (B · BT-046 users/{a}/blockedUsers/{b}) */
  blocked(a: string, b: string): Promise<boolean>;
  /** 매칭 스위치 ON 사용자 UID 목록 */
  matchingUids(limit: number): Promise<string[]>;
}

/** shortcut: 후보를 최대 200명만 본다, 사용자가 늘면 공강 요일 색인 쿼리로 바꾼다. */
const CANDIDATE_LIMIT = 200;

export class FirestoreMatchStore implements MatchStore {
  /** get 에 (ref) => tx.get(ref) 를 넘기면 같은 트랜잭션 안에서 읽는다. */
  constructor(
    private readonly db: Firestore,
    private readonly get: (ref: DocumentReference) => Promise<DocumentSnapshot> = (ref) => ref.get(),
  ) {}

  async settings(uid: string) {
    return parseSettings((await this.get(this.db.doc(`matchSettings/${uid}`))).data());
  }

  async slots(uid: string) {
    const [availability, timetable] = await Promise.all([
      this.get(this.db.doc(`availability/${uid}`)),
      this.get(this.db.doc(`timetables/${uid}`)),
    ]);
    return parseSlots(availability.data(), timetable.data());
  }

  async blocked(a: string, b: string) {
    return (await this.get(this.db.doc(`users/${a}/blockedUsers/${b}`))).exists;
  }

  async matchingUids(limit: number) {
    const snapshot = await this.db.collection("matchSettings").where("isMatching", "==", true).limit(limit).get();
    return snapshot.docs.map((doc) => doc.id);
  }
}

export type SearchResult =
  | { status: "MATCHING_OFF"; candidates: [] }
  | { status: "NO_FREE_SLOT"; candidates: [] }
  | { status: "OK"; reference: Window; candidates: Candidate[] };

async function eitherBlocked(store: MatchStore, a: string, b: string) {
  const [ab, ba] = await Promise.all([store.blocked(a, b), store.blocked(b, a)]);
  return ab || ba;
}

/**
 * 후보 1명 재판정. 검색과 요청 생성(D · BT-019 CandidateResolver)이 같은 규칙을 쓰도록 공유한다.
 * 최소 겹침은 검색자 설정 기준(README "검색자가 고른 최소 겹침 시간 이상"). revision·30분 경계는 parseSlots 가 본다.
 * shortcut: 기존 매칭 충돌은 D 요청 트랜잭션(BT-019)·matches(BT-033)에서 확인한다.
 */
export async function resolveCandidate(
  store: MatchStore,
  uid: string,
  candidateId: string,
  now: Date,
): Promise<Candidate | null> {
  if (candidateId === uid) return null;
  const [me, mySlots, theirs, theirSlots] = await Promise.all([
    store.settings(uid),
    store.slots(uid),
    store.settings(candidateId),
    store.slots(candidateId),
  ]);
  if (!me?.isMatching) return null;
  const ref = referenceWindow(mySlots, me.minOverlapMinutes, now);
  if (!ref) return null;
  const candidate = evaluate(me, ref, candidateId, theirs, theirSlots);
  if (!candidate || (await eitherBlocked(store, uid, candidateId))) return null;
  return candidate;
}

/** 검색 결과는 UID·겹침·목적만 돌려준다. 이름·사진은 앱이 publicProfiles 에서 읽는다(원시 시간표 비노출). */
export async function searchMatches(store: MatchStore, uid: string, now: Date): Promise<SearchResult> {
  const me = await store.settings(uid);
  if (!me?.isMatching) return { status: "MATCHING_OFF", candidates: [] };
  const reference = referenceWindow(await store.slots(uid), me.minOverlapMinutes, now);
  if (!reference) return { status: "NO_FREE_SLOT", candidates: [] };

  const uids = (await store.matchingUids(CANDIDATE_LIMIT + 1)).filter((id) => id !== uid).slice(0, CANDIDATE_LIMIT);
  const results = await Promise.all(
    uids.map(async (id) => {
      const [theirs, theirSlots] = await Promise.all([store.settings(id), store.slots(id)]);
      const candidate = evaluate(me, reference, id, theirs, theirSlots);
      if (!candidate || (await eitherBlocked(store, uid, id))) return null;
      return candidate;
    }),
  );
  // 겹침이 긴 순, 같으면 이른 순. AI 점수 순위는 score.ts rankByScore(BT-028)가 상위 5명 안에서 다시 매긴다.
  const candidates = results
    .filter((c): c is Candidate => c !== null)
    .sort((a, b) => b.overlapMinutes - a.overlapMinutes || a.startMinute - b.startMinute);
  return { status: "OK", reference, candidates };
}
