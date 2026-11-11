package com.team4.bintime.data.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;
import java.util.Objects;

/**
 * 하나의 시간 슬롯을 나타내는 도메인 모델.
 * <p>
 * AGENTS.md 시간 규칙 준수:
 * <ul>
 *   <li>dayOfWeek: 1(월) ~ 7(일)</li>
 *   <li>0 &lt;= startMinute &lt; endMinute &lt;= 1440</li>
 *   <li>구간: [startMinute, endMinute) — 반개구간</li>
 * </ul>
 * <p>
 * 30분 단위 그리드 슬롯 인덱스는 {@code (minute - BASE_HOUR * 60) / 30}으로 계산합니다.
 * 기본 그리드 범위: 09:00 ~ 18:00 (BASE_HOUR = 9, 슬롯 0 ~ 17, 총 18개).
 */
public class TimeSlot implements Parcelable {

    /** 그리드 시작 시각(시 단위). 09:00 → 9 */
    public static final int BASE_HOUR = 9;

    /** 그리드 종료 시각(시 단위). 18:00 → 18 */
    public static final int END_HOUR = 18;

    /** 30분 단위 슬롯 총 개수: (18 - 9) * 2 = 18 */
    public static final int TOTAL_SLOTS = (END_HOUR - BASE_HOUR) * 2;

    /** 요일: 1(월) ~ 7(일). AGENTS.md 표준 준수. */
    private int dayOfWeek;

    /** 시작 시각 — 자정(00:00) 기준 분 단위 정수. 예: 09:00 → 540 */
    private int startMinute;

    /** 종료 시각 — 자정(00:00) 기준 분 단위 정수. 예: 10:30 → 630 */
    private int endMinute;

    public TimeSlot() {
    }

    /**
     * @param dayOfWeek   1(월) ~ 7(일)
     * @param startMinute 0 이상, endMinute 미만
     * @param endMinute   startMinute 초과, 1440 이하
     */
    public TimeSlot(int dayOfWeek, int startMinute, int endMinute) {
        this.dayOfWeek = dayOfWeek;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }

    // ── Getters / Setters ──────────────────────────────

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(int dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public int getStartMinute() {
        return startMinute;
    }

    public void setStartMinute(int startMinute) {
        this.startMinute = startMinute;
    }

    public int getEndMinute() {
        return endMinute;
    }

    public void setEndMinute(int endMinute) {
        this.endMinute = endMinute;
    }

    // ── 계산 메서드 ─────────────────────────────────────

    /** 슬롯 길이(분). */
    public int getDurationMinutes() {
        return endMinute - startMinute;
    }

    /**
     * 30분 단위 그리드 시작 슬롯 인덱스 (0-based, BASE_HOUR 기준).
     * <p>예: 09:00 → 0, 09:30 → 1, 10:00 → 2
     */
    public int getStartSlotIndex() {
        int baseMinutes = BASE_HOUR * 60;
        if (startMinute < baseMinutes) return 0;
        return (startMinute - baseMinutes) / 30;
    }

    /**
     * 30분 단위 그리드 종료 슬롯 인덱스 (exclusive).
     * <p>예: 09:30 → 1, 10:00 → 2, 10:30 → 3
     */
    public int getEndSlotIndex() {
        int baseMinutes = BASE_HOUR * 60;
        if (endMinute <= baseMinutes) return 0;
        return (int) Math.ceil((double) (endMinute - baseMinutes) / 30);
    }

    /**
     * 두 TimeSlot 간 겹침 시간(분) 계산.
     * 같은 dayOfWeek일 때만 의미 있음 (다른 요일은 0 반환).
     * <p>
     * AGENTS.md: {@code overlapMinutes = max(0, min(endA, endB) - max(startA, startB))}
     */
    public int overlapMinutes(TimeSlot other) {
        if (other == null || this.dayOfWeek != other.dayOfWeek) return 0;
        int overlap = Math.min(this.endMinute, other.endMinute)
                - Math.max(this.startMinute, other.startMinute);
        return Math.max(0, overlap);
    }

    /**
     * 이 슬롯이 다른 슬롯과 시간이 겹치는지 (같은 요일 기준).
     */
    public boolean overlaps(TimeSlot other) {
        return overlapMinutes(other) > 0;
    }

    // ── 변환 유틸리티 ────────────────────────────────────

    /**
     * 0-based 요일 인덱스 → dayOfWeek.
     * 월(0)→1, 화(1)→2, … 금(4)→5
     */
    public static int dayIndexToDayOfWeek(int index) {
        return index + 1;
    }

    /**
     * dayOfWeek → 0-based 요일 인덱스.
     * 1(월)→0, 2(화)→1, … 5(금)→4
     */
    public int getDayIndex() {
        return dayOfWeek - 1;
    }

    /**
     * dayOfWeek(1~7) → 한국어 약칭 ("월"~"일").
     */
    public String getDayLabel() {
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
     * 한국어 요일 문자열 → dayOfWeek(1~7). 인식 불가 시 -1.
     * <p>"월", "월요일" → 1 등
     */
    public static int parseDayOfWeek(String dayStr) {
        if (dayStr == null || dayStr.isEmpty()) return -1;
        String first = dayStr.substring(0, 1);
        switch (first) {
            case "월": return 1;
            case "화": return 2;
            case "수": return 3;
            case "목": return 4;
            case "금": return 5;
            case "토": return 6;
            case "일": return 7;
            default: return -1;
        }
    }

    /**
     * "HH:mm" → 분 단위 정수. 파싱 실패 시 -1.
     */
    public static int parseTimeToMinutes(String timeStr) {
        if (timeStr == null || !timeStr.contains(":")) return -1;
        try {
            String[] parts = timeStr.trim().split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 분 단위 정수 → "HH:mm" (24시간제).
     */
    public static String minutesToTimeString(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format(Locale.KOREA, "%02d:%02d", h, m);
    }

    /**
     * UI 표시용 포맷: "월 09:00 ~ 10:30"
     */
    public String toDisplayString() {
        return String.format(Locale.KOREA, "%s %s ~ %s",
                getDayLabel(),
                minutesToTimeString(startMinute),
                minutesToTimeString(endMinute));
    }

    // ── Parcelable ──────────────────────────────────────

    protected TimeSlot(Parcel in) {
        dayOfWeek = in.readInt();
        startMinute = in.readInt();
        endMinute = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(dayOfWeek);
        dest.writeInt(startMinute);
        dest.writeInt(endMinute);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<TimeSlot> CREATOR = new Creator<TimeSlot>() {
        @Override
        public TimeSlot createFromParcel(Parcel in) {
            return new TimeSlot(in);
        }

        @Override
        public TimeSlot[] newArray(int size) {
            return new TimeSlot[size];
        }
    };

    // ── equals / hashCode ───────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeSlot timeSlot = (TimeSlot) o;
        return dayOfWeek == timeSlot.dayOfWeek &&
                startMinute == timeSlot.startMinute &&
                endMinute == timeSlot.endMinute;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dayOfWeek, startMinute, endMinute);
    }

    @Override
    public String toString() {
        return "TimeSlot{" + toDisplayString() + "}";
    }
}
