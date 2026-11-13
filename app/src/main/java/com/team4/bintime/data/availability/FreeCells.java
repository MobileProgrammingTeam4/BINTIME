package com.team4.bintime.data.availability;

import com.team4.bintime.data.model.TimeSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * [BT-016] 사용자가 고른 공강 선택 원본(30분 칸)을 요일별 비트마스크 7개로 다룬다.
 * <p>
 * {@code masks[d - 1]}의 비트 i = 요일 d(1=월 … 7=일)의 자정 기준 칸 i(i*30분 ~ (i+1)*30분).
 * Firestore {@code timetables/{uid}.freeCells}에 정수 7개 목록으로 저장한다. 60분 미만 선택도 원본에는
 * 남고, 매칭 대상({@code availability/{uid}.slots})은 {@link #matchable}로 따로 계산한다.
 * Android 의존성이 없어 JVM 단위 테스트로 검증한다.
 */
public final class FreeCells {

    public static final int DAYS = 7;
    /** 하루 48칸이 모두 켜진 마스크. */
    public static final long FULL_DAY = (1L << SlotGrid.SLOTS_PER_DAY) - 1;

    private FreeCells() {
    }

    public static long[] empty() {
        return new long[DAYS];
    }

    /**
     * 공강 구간 → 마스크. 구간은 30분 경계여야 하며, 형식이 잘못되면 {@link IllegalArgumentException}.
     * 겹치거나 맞닿은 구간은 합쳐진다. null 목록은 빈 선택이다.
     */
    public static long[] fromSlots(List<TimeSlot> slots) {
        long[] masks = empty();
        if (slots == null) return masks;
        for (TimeSlot s : slots) {
            if (s == null) throw new IllegalArgumentException("null slot");
            int day = s.getDayOfWeek();
            int start = s.getStartMinute();
            int end = s.getEndMinute();
            if (day < 1 || day > DAYS || start < 0 || start >= end || end > SlotGrid.MINUTES_PER_DAY
                    || start % SlotGrid.SLOT_MINUTES != 0 || end % SlotGrid.SLOT_MINUTES != 0) {
                throw new IllegalArgumentException("slot is not on the 30-minute grid: " + s);
            }
            for (int cell = start / SlotGrid.SLOT_MINUTES; cell < end / SlotGrid.SLOT_MINUTES; cell++) {
                masks[day - 1] |= 1L << cell;
            }
        }
        return masks;
    }

    /** 마스크 → 요일별 연속 구간(요일·시작 순). 60분 미만 구간도 포함한다. */
    public static List<TimeSlot> toSlots(long[] masks) {
        List<TimeSlot> result = new ArrayList<>();
        if (masks == null) return result;
        for (int d = 0; d < DAYS && d < masks.length; d++) {
            result.addAll(SlotGrid.cellsToSlots(d + 1, masks[d] & FULL_DAY));
        }
        return result;
    }

    /** 수업과 겹치는 칸을 지운 사본. {@link FreeTimeCalculator#selectableCells}를 쓴다. */
    public static long[] keepSelectable(long[] masks, List<TimeSlot> courseSlots) {
        long[] result = empty();
        if (masks == null) return result;
        for (int d = 0; d < DAYS && d < masks.length; d++) {
            result[d] = masks[d] & FreeTimeCalculator.selectableCells(courseSlots, d + 1);
        }
        return result;
    }

    /** 모든 칸이 수업과 겹치지 않으면 true. */
    public static boolean isSelectable(long[] masks, List<TimeSlot> courseSlots) {
        long[] kept = keepSelectable(masks, courseSlots);
        for (int d = 0; d < DAYS; d++) {
            if (kept[d] != (masks == null || d >= masks.length ? 0 : masks[d])) return false;
        }
        return true;
    }

    /** 매칭 대상 공강: 붙은 칸 병합 후 60분 이상({@link FreeTimeCalculator#matchableFreeSlots}). */
    public static List<TimeSlot> matchable(long[] masks) {
        return FreeTimeCalculator.matchableFreeSlots(toSlots(masks));
    }

    /** Firestore 저장용 목록(Long 7개). */
    public static List<Long> toList(long[] masks) {
        List<Long> list = new ArrayList<>(DAYS);
        for (int d = 0; d < DAYS; d++) list.add(masks == null || d >= masks.length ? 0L : masks[d]);
        return list;
    }

    /**
     * Firestore 값 → 마스크. 정수 7개 목록이 아니거나 범위(0 ~ 2^48-1)를 벗어나면 null(형식 깨짐).
     * 필드가 없으면(null) 빈 선택을 돌려준다.
     */
    public static long[] fromList(Object value) {
        if (value == null) return empty();
        if (!(value instanceof List)) return null;
        List<?> list = (List<?>) value;
        if (list.size() != DAYS) return null;
        long[] masks = empty();
        for (int d = 0; d < DAYS; d++) {
            Object v = list.get(d);
            long mask;
            if (v instanceof Long) mask = (Long) v;
            else if (v instanceof Integer) mask = (Integer) v;
            else return null;
            if (mask < 0 || mask > FULL_DAY) return null;
            masks[d] = mask;
        }
        return masks;
    }
}
