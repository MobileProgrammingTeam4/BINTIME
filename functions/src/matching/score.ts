import type { Firestore } from "firebase-admin/firestore";
import { evaluateMatch } from "../ai/internal";
import type { EvaluateMatchInput, EvaluateMatchOutput } from "../ai/tasks/evaluateMatch";
import type { AiResponse } from "../ai/types";
import { type Candidate, PURPOSE_ALL, type Slot } from "./eligibility";

/**
 * A · BT-027. 필수 조건을 통과한 검색 후보 중 겹침이 긴 상위 AI_SCORE_LIMIT 명에게만 AI 점수를 붙인다.
 * AI 입력은 공개 프로필 요약·수업 건물·공통 과목·겹침·목적뿐이다(이름·UID·시간표 원본 금지, BT-024 계약).
 * AI 가 실패하거나 프로필이 모자라면 점수 없이 그대로 둔다(검색은 멈추지 않는다, BT-028 완료 조건).
 */
export const AI_SCORE_LIMIT = 5;

export interface ScoredCandidate extends Candidate {
  score?: number;
  reasons?: string[];
  suggestedActivity?: string;
}

export interface PublicSide {
  department: string;
  grade: number;
  tags: string[];
  bio?: string;
}

export interface CourseInfo {
  name: string;
  classroom?: string;
  slots: Slot[];
}

/** 점수 입력을 읽는 곳. 테스트는 메모리 구현을 쓴다. */
export interface ScoreData {
  profile(uid: string): Promise<PublicSide | null>;
  courses(uid: string): Promise<CourseInfo[]>;
}

export type Evaluator = (uid: string, input: EvaluateMatchInput) => Promise<AiResponse<EvaluateMatchOutput>>;

const clip = (s: unknown, max: number) => (typeof s === "string" ? s.trim().slice(0, max) : "");

/** publicProfiles 의 grade 는 "3학년" 같은 문자열이다. 1~6 이 아니면 null(점수 생략). */
export function toSide(data: Record<string, unknown> | undefined): PublicSide | null {
  if (!data) return null;
  const department = clip(data.department, 40);
  const grade = Number(String(data.grade ?? "").replace(/\D/g, ""));
  if (!department || !Number.isInteger(grade) || grade < 1 || grade > 6) return null;
  const tags = Array.isArray(data.interestTags)
    ? [...new Set(data.interestTags.map((t) => clip(t, 21).replace(/^#/, "").slice(0, 20)).filter(Boolean))].slice(0, 10)
    : [];
  const bio = clip(data.bio, 150);
  return { department, grade, tags, ...(bio ? { bio } : {}) };
}

/** 공강 직전에 끝나는 수업과 직후에 시작하는 수업의 강의실(같은 요일, 각 1개). */
/**
 * 진행 중인 공강은 남은 시간이 매분 줄어 AI 입력이 계속 바뀐다. 30분 단위로 내려 입력을 고정한다.
 * 후보는 이미 최소 60분을 통과했으므로 결과도 60 이상이다.
 * shortcut: Gemini 자체 응답 흔들림은 남는다, BT-028 점수 캐시에서 해결.
 */
export function aiOverlap(minutes: number): number {
  return Math.max(60, Math.floor(minutes / 30) * 30);
}

/** "미래관 611호" → "미래관". 호실은 AI 에 보내지 않는다(팀 결정, AGENTS §6 입력 최소화). 숫자만 있으면 빈 값. */
export function buildingOf(classroom: unknown): string {
  return clip(classroom, 40).replace(/[\s-]*\d.*$/, "").trim();
}

export function nearbyBuildings(courses: CourseInfo[], dayOfWeek: number, start: number, end: number): string[] {
  let before: { at: number; room: string } | null = null;
  let after: { at: number; room: string } | null = null;
  for (const c of courses) {
    const room = buildingOf(c.classroom);
    if (!room) continue;
    for (const s of c.slots) {
      if (s.dayOfWeek !== dayOfWeek) continue;
      if (s.endMinute <= start && (!before || s.endMinute > before.at)) before = { at: s.endMinute, room };
      if (s.startMinute >= end && (!after || s.startMinute < after.at)) after = { at: s.startMinute, room };
    }
  }
  return [...new Set([before?.room, after?.room].filter((r): r is string => !!r))];
}

export function commonCourses(mine: CourseInfo[], theirs: CourseInfo[]): string[] {
  const theirNames = new Set(theirs.map((c) => clip(c.name, 60)));
  return [...new Set(mine.map((c) => clip(c.name, 60)).filter((n) => n && theirNames.has(n)))].slice(0, 10);
}

export async function addScores(
  data: ScoreData,
  evaluate: Evaluator,
  uid: string,
  candidates: Candidate[],
): Promise<ScoredCandidate[]> {
  const top = candidates.slice(0, AI_SCORE_LIMIT);
  if (top.length === 0) return candidates;
  let me: PublicSide | null;
  let myCourses: CourseInfo[];
  try {
    [me, myCourses] = await Promise.all([data.profile(uid), data.courses(uid)]);
  } catch {
    return candidates;
  }
  if (!me) return candidates;
  const scored = await Promise.all(
    top.map(async (c): Promise<ScoredCandidate> => {
      try {
        const [them, theirCourses] = await Promise.all([data.profile(c.candidateId), data.courses(c.candidateId)]);
        if (!them) return c;
        const day = new Date(`${c.meetingDate}T00:00:00Z`).getUTCDay() || 7;
        const { data: out } = await evaluate(uid, {
          overlapMinutes: aiOverlap(c.overlapMinutes),
          purposes: c.purpose === PURPOSE_ALL ? [] : [c.purpose],
          commonCourses: commonCourses(myCourses, theirCourses),
          searcher: { ...me, nearbyBuildings: nearbyBuildings(myCourses, day, c.startMinute, c.endMinute) },
          candidate: { ...them, nearbyBuildings: nearbyBuildings(theirCourses, day, c.startMinute, c.endMinute) },
        });
        return { ...c, score: out.score, reasons: out.reasons, suggestedActivity: out.suggestedActivity };
      } catch {
        return c; // AI·읽기 실패는 점수 없이 보여준다
      }
    }),
  );
  return [...scored, ...candidates.slice(AI_SCORE_LIMIT)];
}

export class FirestoreScoreData implements ScoreData {
  constructor(private readonly db: Firestore) {}

  async profile(uid: string) {
    return toSide((await this.db.doc(`publicProfiles/${uid}`).get()).data());
  }

  async courses(uid: string) {
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
}

export const defaultEvaluator: Evaluator = (uid, input) => evaluateMatch(uid, input);
