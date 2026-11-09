package com.team4.bintime.util;

import com.team4.bintime.data.model.TimeSlot;

import java.util.Locale;

/**
 * 시간 변환 · 포맷팅 · 30분 그리드 파싱 유틸리티.
 * <p>
 * <b>BT-012 시간 규칙:</b>
 * <ul>
 *   <li>Timezone: Asia/Seoul</li>
 *   <li>모든 시각은 24시간제 {@code "HH:mm"} 문자열 또는 자정 기준 분(minute) 정수로 표현</li>
 *   <li>그리드 범위: 09:00 ~ 18:00 → 30분 단위 18개 슬롯 (슬롯 인덱스 0 ~ 17)</li>
 *   <li>슬롯 인덱스 계산: {@code (minutesSinceMidnight - BASE_HOUR * 60) / 30}</li>
 *   <li>겹침 계산: {@code max(0, min(endA, endB) - max(startA, startB))}</li>
 *   <li>유효 최소 겹침 필터: {60, 90, 120, 150, 180}분</li>
 *   <li>구간 표기: [startMinute, endMinute) — 반개구간</li>
 * </ul>
 *
 * <b>24시간제 ↔ 12시간제 변환 규칙:</b>
 * <table>
 *   <tr><td>24h</td><td>12h 표기</td></tr>
 *   <tr><td>00:00</td><td>오전 12:00</td></tr>
 *   <tr><td>01:00 ~ 11:59</td><td>오전 1:00 ~ 오전 11:59</td></tr>
 *   <tr><td>12:00</td><td>오후 12:00</td></tr>
 *   <tr><td>13:00 ~ 23:59</td><td>오후 1:00 ~ 오후 11:59</td></tr>
 * </table>
 */
public final class TimeUtils {

    /** 그리드 시작 시각 (시 단위, 09시). */
    public static final int BASE_HOUR = TimeSlot.BASE_HOUR;

    /** 그리드 종료 시각 (시 단위, 18시). */
    public static final int END_HOUR = TimeSlot.END_HOUR;

    /** 30분 단위 슬롯 총 개수: (18 - 9) * 2 = 18. */
    public static final int TOTAL_SLOTS = TimeSlot.TOTAL_SLOTS;

    /** 유효 최소 겹침 필터 값 목록 (분). */
    public static final int[] VALID_MIN_OVERLAP = {60, 90, 120, 150, 180};

    /** 기본 최소 겹침 (분). */
    public static final int DEFAULT_MIN_OVERLAP = 60;

    private TimeUtils() {
    }

    // ── 24h ↔ 분 변환 ─────────────────────────────────

    /**
     * "HH:mm" → 자정 기준 분 단위 정수.
     * 파싱 실패 시 -1 반환.
     *
     * @param timeStr 24시간제 시간 문자열 (예: "14:30")
     * @return 분 단위 정수 (예: 870), 또는 -1
     */
    public static int parseMinutes(String timeStr) {
        return TimeSlot.parseTimeToMinutes(timeStr);
    }

    /**
     * 분 단위 정수 → "HH:mm" (24시간제).
     *
     * @param minutes 자정 기준 분 (예: 870)
     * @return "HH:mm" 문자열 (예: "14:30")
     */
    public static String formatTime24(int minutes) {
        return TimeSlot.minutesToTimeString(minutes);
    }

    // ── 12시간제 변환 ────────────────────────────────────

    /**
     * 24시간 분 단위 정수 → 12시간제 한국어 표기.
     * <p>예: 870 → "오후 02:30", 0 → "오전 12:00"
     *
     * @param minutes 자정 기준 분
     * @return 한국어 12시간제 문자열 (예: "오후 02:30")
     */
    public static String formatTime12(int minutes) {
        int h24 = minutes / 60;
        int m = minutes % 60;
        String amPm = (h24 < 12) ? "오전" : "오후";
        int h12 = h24 % 12;
        if (h12 == 0) h12 = 12;
        return String.format(Locale.KOREA, "%s %02d:%02d", amPm, h12, m);
    }

    /**
     * 12시간제 구성 요소 → "HH:mm" (24시간제).
     * <p>
     * 오전/오후 판별:
     * <ul>
     *   <li>오전 12시 → "00:mm"</li>
     *   <li>오전 1~11시 → "01:mm" ~ "11:mm"</li>
     *   <li>오후 12시 → "12:mm"</li>
     *   <li>오후 1~11시 → "13:mm" ~ "23:mm"</li>
     * </ul>
     *
     * @param isPm    오후 여부
     * @param hour12  12시간제 시 (1~12)
     * @param minute  분 (0~59)
     * @return "HH:mm" 포맷
     */
    public static String to24HourString(boolean isPm, int hour12, int minute) {
        int hour24;
        if (hour12 == 12) {
            hour24 = isPm ? 12 : 0;
        } else {
            hour24 = isPm ? hour12 + 12 : hour12;
        }
        return String.format(Locale.KOREA, "%02d:%02d", hour24, minute);
    }

    /**
     * "HH:mm" 24시간제 → 오후 여부.
     */
    public static boolean isPm(String timeStr) {
        int minutes = parseMinutes(timeStr);
        if (minutes < 0) return false;
        return (minutes / 60) >= 12;
    }

    /**
     * "HH:mm" 24시간제 → 12시간제 시 (1~12).
     */
    public static int getHour12(String timeStr) {
        int minutes = parseMinutes(timeStr);
        if (minutes < 0) return 12;
        int h24 = minutes / 60;
        int h12 = h24 % 12;
        return (h12 == 0) ? 12 : h12;
    }

    // ── 30분 그리드 슬롯 계산 ─────────────────────────

    /**
     * 분(minutesSinceMidnight) → 30분 그리드 슬롯 인덱스 (0-based).
     * <p>
     * 예: 540(09:00) → 0, 570(09:30) → 1, 600(10:00) → 2
     *
     * @param minutes 자정 기준 분
     * @return 슬롯 인덱스 (범위 밖이면 clamp)
     */
    public static int minutesToSlotIndex(int minutes) {
        int baseMinutes = BASE_HOUR * 60;
        int index = (minutes - baseMinutes) / 30;
        return Math.max(0, Math.min(index, TOTAL_SLOTS - 1));
    }

    /**
     * 슬롯 인덱스 → 해당 슬롯의 시작 분(minutesSinceMidnight).
     * <p>
     * 예: 0 → 540(09:00), 1 → 570(09:30), 10 → 840(14:00)
     */
    public static int slotIndexToStartMinute(int slotIndex) {
        return BASE_HOUR * 60 + slotIndex * 30;
    }

    /**
     * 슬롯 인덱스 → 해당 슬롯의 종료 분(minutesSinceMidnight).
     * <p>
     * 예: 0 → 570(09:30), 1 → 600(10:00)
     */
    public static int slotIndexToEndMinute(int slotIndex) {
        return slotIndexToStartMinute(slotIndex) + 30;
    }

    /**
     * 슬롯 인덱스 → "HH:mm ~ HH:mm" 범위 문자열.
     * <p>
     * 예: 0 → "09:00 ~ 09:30", 10 → "14:00 ~ 14:30"
     *
     * @param slotIndex 0-based 슬롯 인덱스
     * @return 시간 범위 문자열
     */
    public static String getSlotTimeRange(int slotIndex) {
        int startMin = slotIndexToStartMinute(slotIndex);
        int endMin = slotIndexToEndMinute(slotIndex);
        return formatTime24(startMin) + " ~ " + formatTime24(endMin);
    }

    /**
     * 슬롯 인덱스 → 시간 표기 라벨 (그리드 좌측 헤더용).
     * <p>
     * 짝수 슬롯은 정시 표기, 홀수 슬롯은 빈 문자열.
     * <p>예: 0 → "9", 1 → "", 2 → "10", 3 → ""
     */
    public static String getSlotHourLabel(int slotIndex) {
        if (slotIndex % 2 == 0) {
            int hour = BASE_HOUR + slotIndex / 2;
            return String.valueOf(hour);
        }
        return "";
    }

    // ── 겹침 계산 ────────────────────────────────────

    /**
     * 두 시간 구간의 겹침 분 계산.
     * 같은 요일/날짜에 대해서만 호출해야 합니다.
     * <p>
     * AGENTS.md 규칙: {@code max(0, min(endA, endB) - max(startA, startB))}
     *
     * @return 겹침 시간(분), 겹치지 않으면 0
     */
    public static int overlapMinutes(int startA, int endA, int startB, int endB) {
        int overlap = Math.min(endA, endB) - Math.max(startA, startB);
        return Math.max(0, overlap);
    }

    /**
     * 주어진 minOverlap 값이 유효한 필터 값인지 확인.
     * <p>유효 값: {60, 90, 120, 150, 180}
     */
    public static boolean isValidMinOverlap(int minutes) {
        for (int valid : VALID_MIN_OVERLAP) {
            if (valid == minutes) return true;
        }
        return false;
    }

    // ── 요일 유틸리티 ──────────────────────────────────

    /**
     * dayOfWeek(1~7) → 한국어 약칭 ("월"~"일").
     */
    public static String dayOfWeekToLabel(int dayOfWeek) {
        switch (dayOfWeek) {
            case 1: return "월";
            case 2: return "화";
            case 3: return "수";
            case 4: return "목";
            case 5: return "금";
            case 6: return "토";
            case 7: return "일";
            default: return "?";
        }
    }

    /**
     * dayOfWeek(1~7) → 한국어 전체 표기 ("월요일"~"일요일").
     */
    public static String dayOfWeekToFullLabel(int dayOfWeek) {
        return dayOfWeekToLabel(dayOfWeek) + "요일";
    }

    /**
     * 한국어 요일 문자열 → dayOfWeek(1~7). 인식 불가 시 -1.
     */
    public static int parseDayOfWeek(String dayStr) {
        return TimeSlot.parseDayOfWeek(dayStr);
    }
}
