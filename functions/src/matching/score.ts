import { createHash } from "node:crypto";
import { type Firestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import { evaluateMatch } from "../ai/internal";
import { type EvaluateMatchInput, type EvaluateMatchOutput, SYSTEM_INSTRUCTION } from "../ai/tasks/evaluateMatch";
import { stripContacts } from "../ai/contactPolicy";
import type { AiResponse } from "../ai/types";
import { type Candidate, PURPOSE_ALL, type Slot } from "./eligibility";

/**
 * A · BT-027. 필수 조건을 통과한 검색 후보 중 겹침이 긴 상위 AI_SCORE_LIMIT 명에게만 AI 점수를 붙인다.
 * AI 입력은 공개 프로필 요약·수업 건물·공통 과목·겹침·목적뿐이다(이름·UID·시간표 원본 금지, BT-024 계약).
 * AI 가 실패하거나 프로필이 모자라면 점수 없이 그대로 둔다(검색은 멈추지 않는다, BT-028 완료 조건).
 * BT-028: 같은 입력이면 matchScores 캐시를 쓰고(TTL 24시간), 점수 있는 후보를 점수 높은 순으로 앞에 둔다.
 */
export const AI_SCORE_LIMIT = 5;
export const SCORE_CACHE_TTL_MS = 24 * 60 * 60 * 1000;
/**
 * 프롬프트(SYSTEM_INSTRUCTION)와 모델(GEMINI_MODEL)은 해시에 들어가 바뀌면 자동으로 다시 평가된다.
 * 그 밖의 규칙(출력 검사 verify 등)을 바꾸면 이 값을 올린다. 2: 건물명 출력 검사 추가(#102). 3: 근거 없는 이유 제거(BT-049).
 */
export const SCORE_CACHE_VERSION = 3;
/** searchMatches 30초 제한 안에서 검색 결과를 꼭 돌려주기 위한 점수 마감. 늦은 후보는 점수 없이 보낸다. */
export const SCORE_DEADLINE_MS = 15_000;

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

export interface CachedScore {
  inputHash: string;
  score: number;
  reasons: string[];
  suggestedActivity: string;
  expiresAt: number; // epoch ms
}

/** 점수 입력·캐시를 읽고 쓰는 곳. 테스트는 메모리 구현을 쓴다. */
export interface ScoreData {
  profile(uid: string): Promise<PublicSide | null>;
  courses(uid: string): Promise<CourseInfo[]>;
  cached(uid: string, candidateId: string): Promise<CachedScore | null>;
  saveCache(uid: string, candidateId: string, entry: CachedScore): Promise<void>;
}

/** 재평가 기준: 목적·겹침(30분 단위)·양쪽 프로필·수업 건물·공통 과목·프롬프트·모델·버전 중 하나라도 바뀌면 해시가 달라진다. */
export function inputHash(input: EvaluateMatchInput, model: string = process.env.GEMINI_MODEL?.trim() ?? ""): string {
  return createHash("sha256")
    .update(JSON.stringify({ v: SCORE_CACHE_VERSION, prompt: SYSTEM_INSTRUCTION, model, input }))
    .digest("hex");
}

const validCache = (c: CachedScore | null, hash: string, now: number): c is CachedScore =>
  !!c && c.inputHash === hash && c.expiresAt > now && Number.isInteger(c.score) && c.score >= 0 && c.score <= 100;

export type Evaluator = (uid: string, input: EvaluateMatchInput) => Promise<AiResponse<EvaluateMatchOutput>>;

/** 진행 중인 평가. 키에 검색자 UID 가 들어가 사용자끼리 섞이지 않고, 끝나면 바로 지운다(인스턴스 안에서만 합쳐진다). */
const inflight = new Map<string, Promise<EvaluateMatchOutput>>();

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
  // 자기소개 속 이메일·전화번호·링크는 Gemini 에 보내지 않는다(BT-024 입력 최소화). 지운 뒤 비면 생략.
  const bio = stripContacts(clip(data.bio, 150));
  return { department, grade, tags, ...(bio ? { bio } : {}) };
}

/**
 * 진행 중인 공강은 남은 시간이 매분 줄어 AI 입력이 계속 바뀐다. 30분 단위로 내려 입력을 고정한다.
 * 후보는 이미 최소 60분을 통과했으므로 결과도 60 이상이다.
 * 같은 입력이면 matchScores 캐시(BT-028)를 써서 새로고침마다 점수가 바뀌지 않는다.
 */
export function aiOverlap(minutes: number): number {
  return Math.max(60, Math.floor(minutes / 30) * 30);
}

/** "미래관 611호" → "미래관". 호실은 AI 에 보내지 않는다(팀 결정, AGENTS §6 입력 최소화). 숫자만 있으면 빈 값. */
export function buildingOf(classroom: unknown): string {
  return clip(classroom, 40).replace(/[\s-]*\d.*$/, "").trim();
}

/** 공강 직전에 끝나는 수업과 직후에 시작하는 수업의 건물(같은 요일, 각 1개). */
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
  now: number = Date.now(),
  deadlineMs: number = SCORE_DEADLINE_MS,
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
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<null>((resolve) => {
    timer = setTimeout(() => resolve(null), deadlineMs);
  });
  const scoreOne = async (c: Candidate): Promise<ScoredCandidate> => {
    try {
      const [them, theirCourses] = await Promise.all([data.profile(c.candidateId), data.courses(c.candidateId)]);
      if (!them) return c;
      const day = new Date(`${c.meetingDate}T00:00:00Z`).getUTCDay() || 7;
      const input: EvaluateMatchInput = {
        overlapMinutes: aiOverlap(c.overlapMinutes),
        purposes: c.purpose === PURPOSE_ALL ? [] : [c.purpose],
        commonCourses: commonCourses(myCourses, theirCourses),
        searcher: { ...me, nearbyBuildings: nearbyBuildings(myCourses, day, c.startMinute, c.endMinute) },
        candidate: { ...them, nearbyBuildings: nearbyBuildings(theirCourses, day, c.startMinute, c.endMinute) },
      };
      const hash = inputHash(input);
      const hit = await data.cached(uid, c.candidateId).catch(() => null);
      if (validCache(hit, hash, now)) {
        return { ...c, score: hit.score, reasons: hit.reasons, suggestedActivity: hit.suggestedActivity };
      }
      // BT-049: 같은 사람·후보·입력 평가가 동시에 오면(연속 검색) Gemini 호출·한도 차감·저장을 한 번만 한다.
      const key = `${uid}_${c.candidateId}_${hash}`;
      let shared = inflight.get(key);
      if (!shared) {
        shared = evaluate(uid, input)
          .then(async (res) => {
            // Mock 점수는 저장하지 않는다(live 로 바꾼 뒤 가짜 점수가 남지 않게). 저장 실패는 점수 표시를 막지 않는다.
            if (!res.meta.mock) {
              await data
                .saveCache(uid, c.candidateId, { inputHash: hash, ...res.data, expiresAt: now + SCORE_CACHE_TTL_MS })
                .catch(() => undefined);
            }
            return res.data;
          })
          .finally(() => inflight.delete(key));
        inflight.set(key, shared);
      }
      const out = await shared;
      return { ...c, score: out.score, reasons: out.reasons, suggestedActivity: out.suggestedActivity };
    } catch {
      return c; // AI·읽기 실패는 점수 없이 보여준다
    }
  };
  // 마감 뒤 끝난 호출 결과는 버린다(횟수는 이미 차감됨).
  const scored = await Promise.all(top.map((c) => Promise.race([scoreOne(c), deadline]).then((r) => r ?? c)));
  clearTimeout(timer);
  return [...rankByScore(scored), ...candidates.slice(AI_SCORE_LIMIT)];
}

/** AI 추천순: 점수 높은 순, 동점과 점수 없는 후보는 기존 순서(겹침 긴 순)를 유지한다(안정 정렬). */
export function rankByScore(list: ScoredCandidate[]): ScoredCandidate[] {
  return [...list].sort((a, b) => (b.score ?? -1) - (a.score ?? -1));
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

  /** matchScores/{검색자UID}_{후보UID}: 서버 전용(Rules 에 규칙이 없어 앱은 읽기·쓰기 모두 거부). */
  async cached(uid: string, candidateId: string): Promise<CachedScore | null> {
    const d = (await this.db.doc(`matchScores/${uid}_${candidateId}`).get()).data();
    if (!d) return null;
    return {
      inputHash: String(d.inputHash ?? ""),
      score: Number(d.score),
      reasons: Array.isArray(d.reasons) ? d.reasons.map(String) : [],
      suggestedActivity: String(d.suggestedActivity ?? ""),
      expiresAt: d.expiresAt instanceof Timestamp ? d.expiresAt.toMillis() : 0,
    };
  }

  async saveCache(uid: string, candidateId: string, e: CachedScore) {
    await this.db.doc(`matchScores/${uid}_${candidateId}`).set({
      searcherUid: uid,
      candidateUid: candidateId,
      inputHash: e.inputHash,
      score: e.score,
      reasons: e.reasons,
      suggestedActivity: e.suggestedActivity,
      expiresAt: Timestamp.fromMillis(e.expiresAt),
      updatedAt: FieldValue.serverTimestamp(),
    });
  }
}

export const defaultEvaluator: Evaluator = (uid, input) => evaluateMatch(uid, input);
