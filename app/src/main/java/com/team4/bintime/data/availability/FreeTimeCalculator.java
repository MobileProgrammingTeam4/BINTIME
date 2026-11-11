package com.team4.bintime.data.availability;

import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * [BT-016] 공강 산출 · 교집합 · 최소 겹침 판정.
 * <p>
 * 모든 계산은 분 단위 반열린 구간 {@code [start, end)}이며 같은 요일끼리만 비교한다.
 * 실제 날짜(meetingDate)와 "지금/가까운 미래" 선택은 매칭 검색(BT-017) 범위다.
 * 반환 목록의 TimeSlot은 항상 새 인스턴스이고 입력 목록은 변경하지 않는다.
 */
public final class FreeTimeCalculator {

    private static final Comparator<TimeSlot> ORDER = (a, b) -> {
        if (a.getDayOfWeek() != b.getDayOfWeek()) return Integer.compare(a.getDayOfWeek(), b.getDayOfWeek());
        if (a.getStartMinute() != b.getStartMinute()) return Integer.compare(a.getStartMinute(), b.getStartMinute());
        return Integer.compare(a.getEndMinute(), b.getEndMinute());
    };

    private FreeTimeCalculator() {
    }

    /**
     * 유효하지 않은 구간(요일 1..7 밖, start >= end, 0..1440 밖)을 버리고,
     * 같은 요일에서 겹치거나 맞닿은 구간을 합친 뒤 (요일, 시작) 순으로 정렬한다.
     */
    public static List<TimeSlot> normalize(List<TimeSlot> slots) {
        List<TimeSlot> valid = new ArrayList<>();
        if (slots == null) return valid;
        for (TimeSlot s : slots) {
            if (isValid(s)) valid.add(new TimeSlot(s.getDayOfWeek(), s.getStartMinute(), s.getEndMinute()));
        }
        Collections.sort(valid, ORDER);
        List<TimeSlot> merged = new ArrayList<>();
        for (TimeSlot s : valid) {
            TimeSlot last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.getDayOfWeek() == s.getDayOfWeek()
                    && s.getStartMinute() <= last.getEndMinute()) {
                last.setEndMinute(Math.max(last.getEndMinute(), s.getEndMinute()));
            } else {
                merged.add(s);
            }
        }
        return merged;
    }

    /**
     * 수업 사이의 공강(분 단위). 첫 수업 전과 마지막 수업 후는 포함하지 않는다.
     * 수업이 0~1개인 요일과 끝=시작으로 이어지는 수업 사이는 공강이 없다.
     */
    public static List<TimeSlot> gapsBetweenCourses(List<TimeSlot> courseSlots) {
        List<TimeSlot> busy = normalize(courseSlots);
        List<TimeSlot> gaps = new ArrayList<>();
        for (int i = 1; i < busy.size(); i++) {
            TimeSlot prev = busy.get(i - 1);
            TimeSlot next = busy.get(i);
            if (prev.getDayOfWeek() == next.getDayOfWeek()) {
                gaps.add(new TimeSlot(next.getDayOfWeek(), prev.getEndMinute(), next.getStartMinute()));
            }
        }
        return gaps;
    }

    /** 해당 요일에서 수업과 1분도 겹치지 않아 선택 가능한 30분 칸의 비트마스크(비트 i = 칸 i). */
    public static long selectableCells(List<TimeSlot> courseSlots, int dayOfWeek) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("dayOfWeek out of range: " + dayOfWeek);
        }
        long mask = (1L << SlotGrid.SLOTS_PER_DAY) - 1;
        for (TimeSlot course : normalize(courseSlots)) {
            if (course.getDayOfWeek() != dayOfWeek) continue;
            int end = SlotGrid.endSlotOf(course.getEndMinute());
            for (int slot = SlotGrid.startSlotOf(course.getStartMinute()); slot < end; slot++) {
                mask &= ~(1L << slot);
            }
        }
        return mask;
    }

    /** 두 공강 목록의 같은 요일 교집합(분 단위). */
    public static List<TimeSlot> intersect(List<TimeSlot> mine, List<TimeSlot> theirs) {
        List<TimeSlot> a = normalize(mine);
        List<TimeSlot> b = normalize(theirs);
        List<TimeSlot> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            TimeSlot x = a.get(i);
            TimeSlot y = b.get(j);
            if (x.getDayOfWeek() != y.getDayOfWeek()) {
                if (x.getDayOfWeek() < y.getDayOfWeek()) i++;
                else j++;
                continue;
            }
            int overlap = TimeUtils.overlapMinutes(
                    x.getStartMinute(), x.getEndMinute(), y.getStartMinute(), y.getEndMinute());
            if (overlap > 0) {
                int start = Math.max(x.getStartMinute(), y.getStartMinute());
                result.add(new TimeSlot(x.getDayOfWeek(), start, start + overlap));
            }
            if (x.getEndMinute() < y.getEndMinute()) i++;
            else j++;
        }
        return result;
    }

    /** 교집합 중 길이가 minOverlapMinutes 이상인 구간. 허용값은 {60, 90, 120, 150, 180}. */
    public static List<TimeSlot> matchableOverlaps(List<TimeSlot> mine, List<TimeSlot> theirs,
                                                   int minOverlapMinutes) {
        requireValidMinOverlap(minOverlapMinutes);
        List<TimeSlot> result = new ArrayList<>();
        for (TimeSlot s : intersect(mine, theirs)) {
            if (s.getDurationMinutes() >= minOverlapMinutes) result.add(s);
        }
        return result;
    }

    /** overlapMinutes >= minOverlapMinutes. minOverlapMinutes가 허용값이 아니면 예외. */
    public static boolean meetsMinOverlap(int overlapMinutes, int minOverlapMinutes) {
        requireValidMinOverlap(minOverlapMinutes);
        return overlapMinutes >= minOverlapMinutes;
    }

    /**
     * 매칭 대상 공강: 합친 뒤 기본 최소 겹침(60분) 이상인 구간.
     * 30분 칸 선택은 허용하지만 60분 미만 공강은 매칭에서 제외한다(BT-016 수용 기준 ①).
     */
    public static List<TimeSlot> matchableFreeSlots(List<TimeSlot> freeSlots) {
        List<TimeSlot> result = new ArrayList<>();
        for (TimeSlot s : normalize(freeSlots)) {
            if (s.getDurationMinutes() >= TimeUtils.DEFAULT_MIN_OVERLAP) result.add(s);
        }
        return result;
    }

    private static boolean isValid(TimeSlot s) {
        return s != null
                && s.getDayOfWeek() >= 1 && s.getDayOfWeek() <= 7
                && s.getStartMinute() >= 0
                && s.getStartMinute() < s.getEndMinute()
                && s.getEndMinute() <= SlotGrid.MINUTES_PER_DAY;
    }

    private static void requireValidMinOverlap(int minOverlapMinutes) {
        if (!TimeUtils.isValidMinOverlap(minOverlapMinutes)) {
            throw new IllegalArgumentException("invalid minOverlapMinutes: " + minOverlapMinutes);
        }
    }
}
