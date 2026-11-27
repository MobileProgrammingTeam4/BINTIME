import { candidatePlaces, displayName } from "../ai/campusPlaces";
import type { Candidate } from "./eligibility";

export interface PlacePreview {
  /** 첫 장소의 표시 이름(campusPlaces displayName). */
  placeName?: string;
  /** 목적·시간에 맞는 장소 수(placeName 포함). */
  placeCount?: number;
}

/**
 * BT-042 검색 결과 추천 장소 미리보기. AI 없이 E 의 검증 목록(candidatePlaces)에서 목적·겹친 시간에 여는 곳만 고른다.
 * 수락 전이라 두 사람의 수업 건물은 쓰지 않는다(상대 위치 단서 방지). 실제 추천은 수락 뒤 채팅방 recommendPlace 가 한다.
 * 맞는 장소가 없으면 필드를 붙이지 않는다.
 */
export function withPlacePreview<C extends Candidate>(candidate: C, dayOfWeek: number): C & PlacePreview {
  const places = candidatePlaces(candidate.purpose, dayOfWeek, candidate.startMinute, candidate.endMinute);
  if (places.length === 0) return candidate;
  return { ...candidate, placeName: displayName(places[0]), placeCount: places.length };
}
