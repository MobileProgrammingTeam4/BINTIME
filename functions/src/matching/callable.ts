import { randomUUID } from "node:crypto";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { REGION } from "../ai/config";
import { ALLOWED_EMAIL_SUFFIXES_ENV, checkSchoolEmail, parseAllowedEmailSuffixes } from "../ai/emailPolicy";
import { GEMINI_API_KEY } from "../ai/deps";
import type { Candidate } from "./eligibility";
import { withPlacePreview } from "./places";
import { addScores, defaultEvaluator, FirestoreScoreData, type ScoredCandidate } from "./score";
import { FirestoreMatchStore, type MatchStore, searchMatches } from "./search";

function defaultDb() {
  // 이름 있는 앱만 있을 때도 기본 앱을 쓰도록 ai/deps.ts 와 같은 방식(#89 D 리뷰 1번).
  const app = getApps().find((existing) => existing.name === "[DEFAULT]") ?? initializeApp();
  return getFirestore(app);
}

const defaultStore = (): MatchStore => new FirestoreMatchStore(defaultDb());

export type Scorer = (uid: string, candidates: Candidate[]) => Promise<ScoredCandidate[]>;

/** BT-027 상위 후보 AI 점수. 실패해도 후보는 그대로 돌아온다. */
const defaultScorer: Scorer = (uid, candidates) => addScores(new FirestoreScoreData(defaultDb()), defaultEvaluator, uid, candidates);

const defaultSuffixes = () => parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]);

/**
 * A · BT-017. 입력 없이 토큰 UID 의 matchSettings·availability 로 검색한다(필터는 서버 저장값이 기준).
 * 학교 이메일 검사는 AI Callable(#87)과 같은 기준이다. 이메일 원문은 로그에 남기지 않는다.
 */
export function createSearchCallable(
  store: () => MatchStore = defaultStore,
  now: () => Date = () => new Date(),
  suffixes: () => readonly string[] = defaultSuffixes,
  scorer: Scorer = defaultScorer,
) {
  // evaluateMatch 가 Gemini 를 부르므로 AI Callable 과 같은 비밀값을 연결한다.
  return onCall({ region: REGION, timeoutSeconds: 30, maxInstances: 10, secrets: [GEMINI_API_KEY] }, async (request) => {
    if (!request.auth?.uid) {
      throw new HttpsError("unauthenticated", "로그인이 필요합니다.", { reason: "UNAUTHENTICATED", retryable: false });
    }
    const denyCause = checkSchoolEmail(request.auth.token, suffixes());
    if (denyCause) {
      logger.warn("search_matches_denied", { uid: request.auth.uid, cause: denyCause });
      throw new HttpsError("permission-denied", "학교 이메일 인증이 필요합니다.", { reason: "PERMISSION_DENIED", retryable: false });
    }
    try {
      const result = await searchMatches(store(), request.auth.uid, now());
      if (result.status !== "OK") return result;
      // 후보는 모두 기준 공강(reference) 안에서 겹치므로 요일은 reference 것을 쓴다.
      const scored = await scorer(request.auth.uid, result.candidates);
      return { ...result, candidates: scored.map((c) => withPlacePreview(c, result.reference.dayOfWeek)) };
    } catch (error) {
      const requestId = randomUUID();
      logger.error("search_matches_failed", { requestId, message: error instanceof Error ? error.message : String(error) });
      throw new HttpsError("internal", "매칭 검색에 실패했어요.", { reason: "INTERNAL", retryable: true, requestId });
    }
  });
}
