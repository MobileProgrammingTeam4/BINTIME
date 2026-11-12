import { z } from "zod";

/**
 * A · BT-017 매칭 적격성 순수 로직. Firestore 를 모른다.
 * 시간 규칙은 BT-012: 자정 기준 분, [start, end), 요일 1(월)~7(일), Asia/Seoul, 같은 날짜끼리만 비교.
 */

export const VALID_MIN_OVERLAP = [60, 90, 120, 150, 180] as const;
export const DEFAULT_MIN_OVERLAP = 60;
/** 앱 MatchFilter.PURPOSE_ALL. 양쪽 모두 목적 무관일 때 요청 스냅샷에 넣는 값. */
export const PURPOSE_ALL = "전체";
/** 오늘부터 다음 주 같은 요일까지 본다(오늘 이미 지난 공강도 다음 주에 다시 찾도록 8일). */
const SEARCH_DAYS = 8;

const slot = z
  .object({
    dayOfWeek: z.number().int().min(1).max(7),
    startMinute: z.number().int().min(0).max(1439),
    endMinute: z.number().int().min(1).max(1440),
  })
  .refine((s) => s.startMinute < s.endMinute);

export type Slot = z.infer<typeof slot>;

/** matchSettings/{uid} (#77 A 결정). 문서가 없거나 형식이 틀리면 매칭 OFF 로 본다. */
const settings = z.object({
  isMatching: z.boolean(),
  minOverlapMinutes: z
    .number()
    .refine((v) => (VALID_MIN_OVERLAP as readonly number[]).includes(v))
    .catch(DEFAULT_MIN_OVERLAP),
  /** 빈 목록 = "전체" */
  purposes: z.array(z.string().trim().min(1).max(20)).max(5).catch([]),
});

export type MatchSettings = z.infer<typeof settings>;

/**
 * availability/{uid} (C · BT-016, #88) = { slots, sourceRevision, updatedAt }. slots 만 읽는다.
 * 규칙은 원소가 map 인지만 보므로 값 검사는 여기서 한다(#88 합의).
 */
const availability = z.object({ slots: z.array(z.unknown()).max(200) });

export function parseSettings(data: unknown): MatchSettings | null {
  const parsed = settings.safeParse(data);
  return parsed.success ? parsed.data : null;
}

/** 형식이 틀린 슬롯은 버린다. 문서가 없거나 형식이 틀리면 빈 목록. */
export function parseSlots(data: unknown): Slot[] {
  const parsed = availability.safeParse(data);
  if (!parsed.success) return [];
  return parsed.data.slots.flatMap((raw) => {
    const s = slot.safeParse(raw);
    return s.success ? [s.data] : [];
  });
}

export interface SeoulTime {
  date: string; // YYYY-MM-DD
  dayOfWeek: number; // 1~7
  minute: number; // 0~1439
}

/** 한국은 일광절약시간이 없어 UTC+9 고정으로 계산한다. */
export function seoulTime(now: Date, plusDays = 0): SeoulTime {
  const shifted = new Date(now.getTime() + 9 * 3_600_000 + plusDays * 86_400_000);
  return {
    date: shifted.toISOString().slice(0, 10),
    dayOfWeek: shifted.getUTCDay() || 7,
    minute: shifted.getUTCHours() * 60 + shifted.getUTCMinutes(),
  };
}

export interface Window {
  meetingDate: string;
  dayOfWeek: number;
  startMinute: number;
  endMinute: number;
}

/**
 * 내 기준 공강: 지금 겹치는 공강 우선, 없으면 가장 가까운 미래 공강(AGENTS §2 단일 검색 정책).
 * 오늘 진행 중인 공강은 시작을 지금 시각으로 잘라 남은 시간만 쓴다. 남은 길이가 최소 겹침보다 짧으면 건너뛴다.
 */
export function referenceWindow(mySlots: Slot[], minOverlap: number, now: Date): Window | null {
  for (let day = 0; day < SEARCH_DAYS; day++) {
    const t = seoulTime(now, day);
    const today = mySlots
      .filter((s) => s.dayOfWeek === t.dayOfWeek)
      .map((s) => ({ start: day === 0 ? Math.max(s.startMinute, t.minute) : s.startMinute, end: s.endMinute }))
      .filter((s) => s.end - s.start >= minOverlap)
      .sort((a, b) => a.start - b.start);
    if (today.length > 0) {
      return { meetingDate: t.date, dayOfWeek: t.dayOfWeek, startMinute: today[0].start, endMinute: today[0].end };
    }
  }
  return null;
}

/** 기준 공강과 상대 공강의 같은 요일 교집합 중 가장 긴 구간(동점이면 이른 쪽). 최소 겹침 미만이면 null. */
export function bestOverlap(ref: Window, theirSlots: Slot[], minOverlap: number): Window | null {
  let best: Window | null = null;
  for (const s of theirSlots) {
    if (s.dayOfWeek !== ref.dayOfWeek) continue;
    const start = Math.max(ref.startMinute, s.startMinute);
    const end = Math.min(ref.endMinute, s.endMinute);
    if (end - start < minOverlap) continue;
    const longer = !best || end - start > best.endMinute - best.startMinute;
    const tieEarlier = best && end - start === best.endMinute - best.startMinute && start < best.startMinute;
    if (longer || tieEarlier) best = { ...ref, startMinute: start, endMinute: end };
  }
  return best;
}

/** 목적 AND 조건. 빈 목록은 "전체". 맞으면 요청에 쓸 목적, 안 맞으면 null. */
export function commonPurpose(mine: string[], theirs: string[]): string | null {
  if (mine.length === 0) return theirs[0] ?? PURPOSE_ALL;
  if (theirs.length === 0) return mine[0];
  return mine.find((p) => theirs.includes(p)) ?? null;
}

export interface Candidate {
  candidateId: string;
  purpose: string;
  meetingDate: string;
  startMinute: number;
  endMinute: number;
  overlapMinutes: number;
}

/** 후보 1명 판정. 양측 ON·최소 겹침·목적만 본다. 차단은 호출하는 쪽(search.ts)이 확인한다. */
export function evaluate(
  me: MatchSettings,
  ref: Window,
  candidateId: string,
  theirs: MatchSettings | null,
  theirSlots: Slot[],
): Candidate | null {
  if (!me.isMatching || !theirs?.isMatching) return null;
  const purpose = commonPurpose(me.purposes, theirs.purposes);
  if (purpose === null) return null;
  const overlap = bestOverlap(ref, theirSlots, me.minOverlapMinutes);
  if (!overlap) return null;
  return {
    candidateId,
    purpose,
    meetingDate: overlap.meetingDate,
    startMinute: overlap.startMinute,
    endMinute: overlap.endMinute,
    overlapMinutes: overlap.endMinute - overlap.startMinute,
  };
}
