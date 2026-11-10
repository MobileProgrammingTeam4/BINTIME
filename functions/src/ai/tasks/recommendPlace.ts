import { z } from "zod";
import { requireParticipant } from "../access";
import { AiError } from "../errors";
import type { AiDeps, AiTask } from "../types";
import { matchIdSchema, text } from "./shared";

/**
 * E(BT-042) 교내 만남 장소 추천. 앱은 matchId 만 보내고, 시간·건물은 서버가 matches 스냅샷에서 읽는다(BT-042).
 * 모델은 허용 목록의 placeId 만 고를 수 있고, 목록 밖 id 는 버린다. 이름은 서버 목록 값으로 채운다.
 * live 프롬프트와 검증된 국민대 장소 목록은 BT-042 에서 추가한다.
 */
export interface CampusPlace {
  placeId: string;
  name: string;
}

/** 검증된 국민대 장소 목록. BT-042 에서 채운다. 비어 있는 동안 live 결과는 모두 버려진다. */
export const VERIFIED_CAMPUS_PLACES: CampusPlace[] = [];

/** Mock 전용. Figma 시안 문구이며 검증된 장소 목록이 아니다. */
export const MOCK_CAMPUS_PLACES: CampusPlace[] = [
  { placeId: "mock-welfare-b1-cafe", name: "복지관 지하1층 카페" },
  { placeId: "mock-bugak-library", name: "북악도서관" },
];

export function allowedPlaces(deps: Pick<AiDeps, "mode">): CampusPlace[] {
  return deps.mode === "mock" ? MOCK_CAMPUS_PLACES : VERIFIED_CAMPUS_PLACES;
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

export const recommendPlaceTask: AiTask<RecommendPlaceInput, RecommendPlaceOutput> = {
  name: "recommendPlace",
  input,
  output,
  timeoutMs: 10_000,
  dailyLimit: 30,
  authorize: ({ matchId }, ctx, deps) => requireParticipant(deps.matches, matchId, ctx.uid),
  verify: (out, _input, deps) => {
    const byId = new Map(allowedPlaces(deps).map((place) => [place.placeId, place]));
    const seen = new Set<string>();
    const places = out.places.flatMap((place) => {
      const known = byId.get(place.placeId);
      if (!known || seen.has(known.placeId)) return [];
      seen.add(known.placeId);
      return [{ placeId: known.placeId, name: known.name, reason: place.reason }];
    });
    if (places.length === 0) throw new AiError("AI_INVALID_OUTPUT", "no verified place", { transient: true });
    return { places };
  },
  mock: () => ({
    places: [
      { placeId: "mock-welfare-b1-cafe", name: "복지관 지하1층 카페", reason: "두 분 모두 직전 수업 건물에서 가까워요" },
      { placeId: "mock-bugak-library", name: "북악도서관", reason: "조용히 스터디하기 좋아요" },
    ],
  }),
};
