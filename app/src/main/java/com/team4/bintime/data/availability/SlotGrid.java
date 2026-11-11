package com.team4.bintime.data.availability;

import com.team4.bintime.data.model.TimeSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * [BT-016] 자정 기준 30분 칸 변환.
 * <p>
 * 칸 번호 = {@code minute / 30}, 하루 48칸(0 ~ 47). 계약(저장·비교)은 분 단위이며 칸은 선택 UI 단위다.
 * <p>
 * 기존 {@code TimeUtils}/{@code TimeSlot}의 09:00 기준 행과의 관계: {@code 칸 = 행 + 18}.
 * 09:00 ~ 18:00 범위에서만 성립한다(기존 메서드는 범위 밖 값을 잘라낸다).
 */
public final class SlotGrid {

    public static final int SLOT_MINUTES = 30;
    public static final int MINUTES_PER_DAY = 1440;
    public static final int SLOTS_PER_DAY = MINUTES_PER_DAY / SLOT_MINUTES;

    private SlotGrid() {
    }

    /** 0..1439분 → 해당 시각이 속한 칸(0..47). */
    public static int slotOf(int minute) {
        if (minute < 0 || minute >= MINUTES_PER_DAY) {
            throw new IllegalArgumentException("minute out of range: " + minute);
        }
        return minute / SLOT_MINUTES;
    }

    /** 구간 시작 분 → 시작 칸(내림). */
    public static int startSlotOf(int startMinute) {
        return slotOf(startMinute);
    }

    /** 구간 종료 분 → 종료 칸(올림, exclusive). 1440 → 48. */
    public static int endSlotOf(int endMinute) {
        if (endMinute <= 0 || endMinute > MINUTES_PER_DAY) {
            throw new IllegalArgumentException("endMinute out of range: " + endMinute);
        }
        return (endMinute + SLOT_MINUTES - 1) / SLOT_MINUTES;
    }

    public static int slotStartMinute(int slot) {
        checkSlot(slot);
        return slot * SLOT_MINUTES;
    }

    public static int slotEndMinute(int slot) {
        checkSlot(slot);
        return slot * SLOT_MINUTES + SLOT_MINUTES;
    }

    /**
     * 같은 요일에서 선택한 칸(비트 i = 칸 i)을 연속 구간으로 합친다.
     * 하루 안에서만 합치며 자정을 넘겨 잇지 않는다.
     */
    public static List<TimeSlot> cellsToSlots(int dayOfWeek, long cellMask) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("dayOfWeek out of range: " + dayOfWeek);
        }
        if ((cellMask >>> SLOTS_PER_DAY) != 0) {
            throw new IllegalArgumentException("cellMask has bits beyond slot 47");
        }
        List<TimeSlot> result = new ArrayList<>();
        int slot = 0;
        while (slot < SLOTS_PER_DAY) {
            if ((cellMask & (1L << slot)) == 0) {
                slot++;
                continue;
            }
            int start = slot;
            while (slot < SLOTS_PER_DAY && (cellMask & (1L << slot)) != 0) slot++;
            result.add(new TimeSlot(dayOfWeek, start * SLOT_MINUTES, slot * SLOT_MINUTES));
        }
        return result;
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SLOTS_PER_DAY) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
    }
}
