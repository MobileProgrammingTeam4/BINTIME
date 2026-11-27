import { z } from "zod";
import { nearbyBuildings } from "../../matching/score";
import { requireParticipant } from "../access";
import { type CampusPlace, CAMPUS_PLACES_VERSION, candidatePlaces, displayName } from "../campusPlaces";
import { AiError } from "../errors";
import type { PlaceMatch } from "../placeStore";
import type { AiDeps, AiTask } from "../types";
import { matchIdSchema, text } from "./shared";

/**
 * E · BT-042 교내 만남 장소 추천 (docs/BT-042.md). 앱은 matchId 만 보내고, 서버가 matches 스냅샷(목적·요일·시간)과
 * 두 사람의 공강 직전·직후 수업 건물을 읽는다.
 * - 후보는 검증된 목록(campusPlaces.ts) 중 목적에 맞고 약속 시간에 여는 곳뿐이다. 모델은 후보의 placeId 만 고르고,
 *   후보 밖 id 는 버린다. 이름은 서버 목록 값으로 채운다.
 * - 같은 매칭은 placeRecommendations/{matchId} 에 저장해 두 사람이 같은 추천을 보고, 다시 열어도 호출량을 쓰지 않는다.
 * - 이유 문구에는 두 사람의 수업 건물과 운영 시간을 쓰지 않는다(프롬프트 + 서버 검사).
 */
export interface PlaceOption {
  placeId: string;
  name: string;
}

/** Mock 전용. Figma 시안 문구이며 검증된 장소 목록이 아니다. */
export const MOCK_CAMPUS_PLACES: PlaceOption[] = [
  { placeId: "mock-welfare-b1-cafe", name: "복지관 지하1층 카페" },
  { placeId: "mock-bugak-library", name: "북악도서관" },
];

const DAY_NAMES = ["", "월", "화", "수", "목", "금", "토", "일"];
const hhmm = (minute: number) => `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;

/** 이유 문구가 규칙을 어기면 대신 쓰는 문구 */
export const FALLBACK_REASON = "두 분 공강 시간에 함께 가기 좋아요";
/** 운영 시간·시각으로 보이는 표현 */
const HOURS_PATTERN = /\d{1,2}\s*시|\d{1,2}:\d{2}|운영|영업|오픈|마감/;

export const SYSTEM_INSTRUCTION = [
  "너는 국민대학교 학생 두 명이 공강에 만날 교내 장소를 고르는 도우미다. 데이팅 앱이 아니다.",
  "입력의 places 목록에 있는 장소만 고른다. 목록에 없는 장소·건물·매장을 만들지 않는다.",
  "만남 목적(purpose)에 맞는 곳, 두 사람의 공강 직전·직후 수업 건물(personA, personB)과 같은 건물에 있는 곳을 우선한다.",
  "건물 사이 거리나 걸리는 시간은 단정하지 않는다.",
  "1~3곳을 고르고, 각 reason 은 한국어 한 문장(80자 이내)으로 쓴다.",
  "reason 에는 두 사람의 수업 건물 이름, 운영 시간, 가격, 메뉴처럼 입력에 없는 사실을 쓰지 않는다.",
  "응답은 지정된 JSON 형식만 사용한다.",
].join("\n");

interface PlaceContext {
  match: PlaceMatch;
  candidates: CampusPlace[];
  /** 두 사람의 공강 직전·직후 수업 건물(합친 것). 이유 문구 검사에 쓴다. */
  buildings: string[];
  personA: string[];
  personB: string[];
}

// 같은 요청 안에서 fromCache·buildRequest·verify 가 한 번 읽은 내용을 같이 쓴다(runAiTask 는 같은 input 객체를 넘긴다).
const contexts = new WeakMap<object, Promise<PlaceContext>>();

function loadContext(input: RecommendPlaceInput, uid: string, deps: AiDeps): Promise<PlaceContext> {
  let pending = contexts.get(input);
  if (!pending) {
    pending = readContext(input.matchId, uid, deps);
    contexts.set(input, pending);
  }
  return pending;
}

async function readContext(matchId: string, uid: string, deps: AiDeps): Promise<PlaceContext> {
  const store = deps.places;
  if (!store) throw new AiError("AI_UNAVAILABLE", "place store is not configured");
  const match = await store.match(matchId);
  if (!match || !match.participantUids.includes(uid)) throw new AiError("PERMISSION_DENIED", "not a match participant");
  // 내 것을 personA 로 두되 모델에는 누가 누구인지(UID·이름) 보내지 않는다.
  const others = match.participantUids.filter((p) => p !== uid);
  const [mine, theirs] = await Promise.all([store.courses(uid), others[0] ? store.courses(others[0]) : Promise.resolve([])]);
  const personA = nearbyBuildings(mine, match.dayOfWeek, match.startMinute, match.endMinute);
  const personB = nearbyBuildings(theirs, match.dayOfWeek, match.startMinute, match.endMinute);
  const candidates = candidatePlaces(match.purpose, match.dayOfWeek, match.startMinute, match.endMinute);
  return { match, candidates, buildings: [...new Set([...personA, ...personB])], personA, personB };
}

/** 이유 문구 검사: 장소 자신의 건물이 아닌 수업 건물 이름이나 운영 시간 표현이 있으면 기본 문구로 바꾼다. */
export function safeReason(reason: string, place: CampusPlace, buildings: string[]): string {
  const leaks = buildings.some((b) => b && b !== place.building && reason.includes(b));
  return leaks || HOURS_PATTERN.test(reason) ? FALLBACK_REASON : reason;
}

const input = z.object({ matchId: matchIdSchema }).strict();

const output = z
  .object({
    places: z
      .array(
        z
          .object({
            placeId: text(1, 64),
            name: text(1, 60),
            reason: text(1, 80),
          })
          .strict(),
      )
      .min(1)
      .max(3),
  })
  .strict();

export type RecommendPlaceInput = z.infer<typeof input>;
export type RecommendPlaceOutput = z.infer<typeof output>;

/** 후보에서 id 를 찾아 서버 이름·검사한 이유로 바꾼다. 후보 밖·중복은 버린다. */
function keepCandidates(places: { placeId: string; reason: string }[], ctx: PlaceContext): RecommendPlaceOutput["places"] {
  const byId = new Map(ctx.candidates.map((p) => [p.placeId, p]));
  const seen = new Set<string>();
  return places.flatMap((item) => {
    const known = byId.get(item.placeId);
    if (!known || seen.has(known.placeId)) return [];
    seen.add(known.placeId);
    return [{ placeId: known.placeId, name: displayName(known), reason: safeReason(item.reason, known, ctx.buildings) }];
  }).slice(0, 3);
}

// verify 는 동기 함수라, live 에서 읽어 둔 내용을 여기서 꺼낸다.
const resolved = new WeakMap<object, PlaceContext>();

export const recommendPlaceTask: AiTask<RecommendPlaceInput, RecommendPlaceOutput> = {
  name: "recommendPlace",
  input,
  output,
  timeoutMs: 10_000,
  dailyLimit: 30,
  authorize: ({ matchId }, ctx, deps) => requireParticipant(deps.matches, matchId, ctx.uid),
  fromCache: async (value, ctx, deps) => {
    const context = await loadContext(value, ctx.uid, deps);
    resolved.set(value, context);
    // 약속 시간에 여는 장소가 없으면 Gemini 를 부르지 않고 횟수도 쓰지 않는다. 앱은 장소 줄을 숨긴다.
    if (context.candidates.length === 0) throw new AiError("AI_UNAVAILABLE", "no open place", { retryable: false });
    const stored = await deps.places!.cached(value.matchId);
    if (!stored || stored.version !== CAMPUS_PLACES_VERSION) return null;
    const places = keepCandidates(stored.places, context);
    return places.length > 0 ? { places } : null;
  },
  saveCache: async (out, value, _ctx, deps) => {
    await deps.places!.save(value.matchId, {
      version: CAMPUS_PLACES_VERSION,
      places: out.places.map(({ placeId, reason }) => ({ placeId, reason })),
    });
  },
  verify: (out, value, deps) => {
    if (deps.mode === "mock") {
      const byId = new Map(MOCK_CAMPUS_PLACES.map((place) => [place.placeId, place]));
      const seen = new Set<string>();
      const places = out.places.flatMap((place) => {
        const known = byId.get(place.placeId);
        if (!known || seen.has(known.placeId)) return [];
        seen.add(known.placeId);
        return [{ placeId: known.placeId, name: known.name, reason: place.reason }];
      });
      if (places.length === 0) throw new AiError("AI_INVALID_OUTPUT", "no verified place", { transient: true });
      return { places };
    }
    const context = resolved.get(value);
    if (!context) throw new AiError("AI_INVALID_OUTPUT", "place context missing");
    const places = keepCandidates(out.places, context);
    if (places.length === 0) throw new AiError("AI_INVALID_OUTPUT", "no verified place", { transient: true });
    return { places };
  },
  mock: () => ({
    places: [
      { placeId: "mock-welfare-b1-cafe", name: "복지관 지하1층 카페", reason: "두 분 모두 직전 수업 건물에서 가까워요" },
      { placeId: "mock-bugak-library", name: "북악도서관", reason: "조용히 스터디하기 좋아요" },
    ],
  }),
  buildRequest: async (value, ctx, deps) => {
    const context = await loadContext(value, ctx.uid, deps);
    resolved.set(value, context);
    const { match } = context;
    const payload = {
      purpose: match.purpose ?? "전체",
      day: DAY_NAMES[match.dayOfWeek] ?? "",
      time: `${hhmm(match.startMinute)}~${hhmm(match.endMinute)}`,
      personA: { nearbyClassBuildings: context.personA },
      personB: { nearbyClassBuildings: context.personB },
      places: context.candidates.map((p) => ({ placeId: p.placeId, name: p.name, building: p.building, floor: p.floor, category: p.category })),
    };
    return { systemInstruction: SYSTEM_INSTRUCTION, contents: JSON.stringify(payload), temperature: 0.4, maxOutputTokens: 512 };
  },
};
