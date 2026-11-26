import { Timestamp, type Firestore } from "firebase-admin/firestore";
import type { Slot } from "../matching/eligibility";
import type { CourseInfo } from "../matching/score";

/**
 * E · BT-042 장소 추천이 읽고 쓰는 곳. 서버(Admin SDK)만 쓴다.
 * - 매칭 스냅샷: matches/{matchId} 의 목적·요일·시간·참여자(BT-031 이 수락 때 복사)
 * - 수업: timetables/{uid}/courses (BT-027 FirestoreScoreData.courses 와 같은 읽기, 건물명만 쓴다).
 *   matching/score 의 클래스를 직접 쓰면 deps → score → ai/internal → deps 순환이 생겨 타입만 가져온다.
 * - 저장된 추천: placeRecommendations/{matchId}. Firestore Rules 에 규칙이 없어 기본 거부라 앱은 읽기·쓰기 모두 못 한다.
 */
export interface PlaceMatch {
  participantUids: string[];
  purpose: string | null;
  dayOfWeek: number;
  startMinute: number;
  endMinute: number;
}

export interface StoredRecommendation {
  version: number;
  places: { placeId: string; reason: string }[];
}

export interface PlaceStore {
  match(matchId: string): Promise<PlaceMatch | null>;
  courses(uid: string): Promise<CourseInfo[]>;
  cached(matchId: string): Promise<StoredRecommendation | null>;
  save(matchId: string, entry: StoredRecommendation): Promise<void>;
}

function toMatch(d: Record<string, unknown> | undefined): PlaceMatch | null {
  if (!d) return null;
  const { participantUids, purpose, dayOfWeek, startMinute, endMinute } = d;
  if (!Array.isArray(participantUids) || typeof dayOfWeek !== "number" || typeof startMinute !== "number"
    || typeof endMinute !== "number") return null;
  return {
    participantUids: participantUids.filter((u): u is string => typeof u === "string"),
    purpose: typeof purpose === "string" ? purpose : null,
    dayOfWeek, startMinute, endMinute,
  };
}

function toStored(d: Record<string, unknown> | undefined): StoredRecommendation | null {
  if (!d || typeof d.version !== "number" || !Array.isArray(d.places)) return null;
  const places = d.places.flatMap((p) => {
    const item = p as { placeId?: unknown; reason?: unknown };
    return typeof item.placeId === "string" && typeof item.reason === "string" ? [{ placeId: item.placeId, reason: item.reason }] : [];
  });
  return { version: d.version, places };
}

export class FirestorePlaceStore implements PlaceStore {
  constructor(private readonly db: Firestore, private readonly now: () => Date = () => new Date()) {}

  async match(matchId: string) {
    return toMatch((await this.db.doc(`matches/${matchId}`).get()).data());
  }

  async courses(uid: string): Promise<CourseInfo[]> {
    const snapshot = await this.db.collection(`timetables/${uid}/courses`).get();
    return snapshot.docs.map((doc) => {
      const d = doc.data();
      return {
        name: String(d.name ?? ""),
        classroom: typeof d.classroom === "string" ? d.classroom : undefined,
        slots: Array.isArray(d.timeSlots) ? (d.timeSlots as Slot[]) : [],
      };
    });
  }

  async cached(matchId: string) {
    return toStored((await this.db.doc(`placeRecommendations/${matchId}`).get()).data());
  }

  async save(matchId: string, entry: StoredRecommendation) {
    // 이유 문구와 placeId 만 둔다. 이름은 읽을 때 서버 목록 값으로 채운다.
    await this.db.doc(`placeRecommendations/${matchId}`).set({ ...entry, matchId, updatedAt: Timestamp.fromDate(this.now()) });
  }
}

/** 테스트용 */
export class MemoryPlaceStore implements PlaceStore {
  readonly saved = new Map<string, StoredRecommendation>();
  constructor(private readonly matches: Record<string, PlaceMatch> = {}, private readonly courseMap: Record<string, CourseInfo[]> = {}) {}

  async match(matchId: string) { return this.matches[matchId] ?? null; }
  async courses(uid: string) { return this.courseMap[uid] ?? []; }
  async cached(matchId: string) { return this.saved.get(matchId) ?? null; }
  async save(matchId: string, entry: StoredRecommendation) { this.saved.set(matchId, entry); }
}
