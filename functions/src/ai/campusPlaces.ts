/**
 * E · BT-042 검증된 국민대 교내 장소 목록. 모델은 이 목록의 placeId 만 고를 수 있다(AGENTS §6).
 *
 * - 국민대 공식 페이지(kookmin.ac.kr)에 적힌 장소만 넣는다. 장소마다 출처(source)와 확인 날짜(verifiedAt)를 둔다.
 * - 운영 시간(hours)은 학기 기준이며 약속 시간에 열려 있지 않은 곳을 후보에서 빼는 데만 쓴다. 이유 문구·프롬프트에는 넣지 않는다.
 *   페이지에 요일이 없으면 days 는 null(요일로는 거르지 않음), 시간이 불확실하면 hours 를 null 로 두어 거르지 않는다.
 *   구간마다 요일이 다르면(예: 금요일 석식 미운영) 그 구간에만 days 를 둔다.
 * - 장소를 더하거나 빼거나 고치면 CAMPUS_PLACES_VERSION 을 올린다. 저장된 추천(placeRecommendations)은 버전이 다르면 다시 만든다.
 */

export const CAMPUS_PLACES_VERSION = 2;
export const CAMPUS_PLACES_VERIFIED_AT = "2026-11-26";

/** 교내복지시설(식당·카페·편의점·기타 매장) */
export const SOURCE_WELFARE = "https://www.kookmin.ac.kr/comm/menu/user/94b421adc517815ccfa1c53d043d7d72/content/index.do";
/** 생활협동조합 매장 안내 */
export const SOURCE_COOP = "http://kmucoop.kookmin.ac.kr/";

/** 앱 MatchFilter.PURPOSE_OPTIONS 와 같은 값 */
export type Purpose = "스터디" | "밥" | "카페·디저트" | "산책";
export type PlaceCategory = "CAFE" | "RESTAURANT" | "DRINK" | "BAKERY";

export interface OpeningHours {
  /** 1(월)~7(일). 페이지에 요일이 없으면 null */
  days: number[] | null;
  /** 자정부터의 분, [open, close). days 가 있으면 그 구간은 그 요일에만 연다(장소 전체 days 와 함께 적용) */
  ranges: { open: number; close: number; days?: number[] }[];
}

export interface CampusPlace {
  placeId: string;
  /** 공식 페이지 표기 */
  name: string;
  /** 페이지에 적힌 건물 */
  building: string;
  /** 페이지에 적힌 층·위치 */
  floor: string;
  category: PlaceCategory;
  purposes: Purpose[];
  /** 학기 기준. 불확실하면 null */
  hours: OpeningHours | null;
  source: string[];
  verifiedAt: string;
}

const t = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
};
const WEEKDAYS = [1, 2, 3, 4, 5];
const range = (open: string, close: string, days?: number[]) => (days ? { open: t(open), close: t(close), days } : { open: t(open), close: t(close) });
const hours = (days: number[] | null, ...ranges: OpeningHours["ranges"]): OpeningHours => ({ days, ranges });

const CAFE: Purpose[] = ["카페·디저트", "스터디"];
const MEAL: Purpose[] = ["밥"];
const DESSERT: Purpose[] = ["카페·디저트"];

const place = (placeId: string, name: string, building: string, floor: string, category: PlaceCategory,
  purposes: Purpose[], opening: OpeningHours | null, source: string[] = [SOURCE_WELFARE]): CampusPlace =>
  ({ placeId, name, building, floor, category, purposes, hours: opening, source, verifiedAt: CAMPUS_PLACES_VERIFIED_AT });

export const VERIFIED_CAMPUS_PLACES: readonly CampusPlace[] = [
  place("law-cafe", "법학관 카페 (쿱스켓 내부)", "법학관", "1층", "CAFE", CAFE, hours(WEEKDAYS, range("09:00", "17:30"))),
  place("science-cafe", "과학관 카페 (쿱스켓 내부)", "과학관", "1층", "CAFE", CAFE, hours(WEEKDAYS, range("08:30", "17:30"))),
  place("welfare-cafe-namu", "복지관 카페 (카페나무)", "복지관", "지하1층", "CAFE", CAFE, hours(WEEKDAYS, range("08:30", "20:00"))),
  place("headquarters-cafe", "본부관 카페", "본부관", "1층", "CAFE", CAFE, hours(WEEKDAYS, range("08:30", "17:00"))),
  place("bugak-cafe", "북악관 카페", "북악관", "1층", "CAFE", CAFE, hours(WEEKDAYS, range("08:00", "18:00"))),
  place("engineering-cafe", "공학관 카페", "공학관", "1층", "CAFE", CAFE, hours(WEEKDAYS, range("08:30", "17:30"))),
  place("arts-cafe-namu", "예술관 카페 (카페나무)", "예술관", "1층", "CAFE", CAFE, hours(WEEKDAYS, range("08:30", "20:00"))),
  place("library-cafe-hollys", "도서관 카페 (쿱카페 할리스)", "성곡도서관", "지하1층", "CAFE", CAFE,
    hours(WEEKDAYS, range("10:00", "19:00")), [SOURCE_WELFARE, SOURCE_COOP]),
  place("bugak-gongcha", "공차", "북악관", "1층", "DRINK", DESSERT, hours(null, range("10:00", "18:00"))),
  place("welfare-student-cafeteria", "학생식당", "종합복지관", "1층", "RESTAURANT", MEAL,
    hours(null, range("08:00", "15:00"), range("17:00", "18:30"))),
  // 학기: 중식 11:30~14:00, 석식 17:00~18:30(금요일 석식 미운영), 주말·공휴일 휴점. 페이지에 이용 대상 제한 표기 없음.
  place("welfare-staff-cafeteria", "교직원식당", "종합복지관", "1층", "RESTAURANT", MEAL,
    hours(WEEKDAYS, range("11:30", "14:00"), range("17:00", "18:30", [1, 2, 3, 4]))),
  place("law-hanul", "한울식당", "법학관", "지하1층", "RESTAURANT", MEAL, hours(null, range("10:30", "18:30")), [SOURCE_WELFARE, SOURCE_COOP]),
  place("law-cheonghyang-korean", "청향 (한식당)", "법학관", "5층", "RESTAURANT", MEAL,
    hours(null, range("11:30", "14:00"), range("17:00", "19:00")), [SOURCE_WELFARE, SOURCE_COOP]),
  place("law-western", "양식당", "법학관", "5층", "RESTAURANT", ["밥", "카페·디저트"],
    hours(null, range("11:30", "14:00"), range("15:00", "18:00")), [SOURCE_WELFARE, SOURCE_COOP]),
  place("welfare-kbob", "도시락전문점 (K-BOB+)", "종합복지관", "1층", "RESTAURANT", MEAL, hours(null, range("11:00", "17:00"))),
  place("bugak-kgimbob", "김밥ㆍ분식전문점 (K-GIMBOB)", "북악관", "1층", "RESTAURANT", MEAL, hours(null, range("11:00", "17:30"))),
  place("bugak-subway", "써브웨이", "북악관", "1층", "RESTAURANT", MEAL, hours(null, range("08:30", "19:00"))),
  place("welfare-saladdy", "샐러디", "종합복지관", "지하1층", "RESTAURANT", MEAL, hours(null, range("10:30", "18:30"))),
  place("welfare-burgerun", "버거운버거", "종합복지관", "지하1층", "RESTAURANT", MEAL, hours(null, range("09:00", "20:00"))),
  place("engineering-ozi-burger", "오지버거", "공학관", "1층", "RESTAURANT", MEAL, hours(null, range("10:30", "20:00"))),
  place("welfare-bakery", "제과점", "종합복지관", "2층", "BAKERY", DESSERT, hours(null, range("08:30", "18:00"))),
];

/** 채팅 상단에 보일 이름. 공식 이름에 건물이 없으면 페이지에 적힌 건물·층을 붙인다. */
export function displayName(place: CampusPlace): string {
  return place.name.includes(place.building) ? place.name : `${place.name} (${place.building} ${place.floor})`;
}

/** 약속 구간 [start, end) 동안 30분 이상(약속이 더 짧으면 약속 전체) 열려 있는지. 운영 시간이 불확실하면 열려 있다고 본다. */
export function isOpenDuring(place: CampusPlace, dayOfWeek: number, start: number, end: number): boolean {
  if (!place.hours) return true;
  if (place.hours.days && !place.hours.days.includes(dayOfWeek)) return false;
  const need = Math.min(30, Math.max(0, end - start));
  const open = place.hours.ranges.filter((r) => !r.days || r.days.includes(dayOfWeek))
    .reduce((sum, r) => sum + Math.max(0, Math.min(end, r.close) - Math.max(start, r.open)), 0);
  return open >= need && open > 0;
}

/** 목적에 맞고 약속 시간에 여는 장소. 목적이 전체·산책·알 수 없음이면 목적으로는 거르지 않는다. */
export function candidatePlaces(purpose: string | null, dayOfWeek: number, start: number, end: number,
  places: readonly CampusPlace[] = VERIFIED_CAMPUS_PLACES): CampusPlace[] {
  const known = (["스터디", "밥", "카페·디저트"] as string[]).includes(purpose ?? "");
  return places.filter((p) => (!known || p.purposes.includes(purpose as Purpose)) && isOpenDuring(p, dayOfWeek, start, end));
}
