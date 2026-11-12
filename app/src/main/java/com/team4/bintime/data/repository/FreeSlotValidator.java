package com.team4.bintime.data.repository;

import com.team4.bintime.data.availability.Availability;
import com.team4.bintime.data.availability.FreeCells;
import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * [BT-016] 공강 선택 저장 전 검증. Mock·Firestore 저장소가 같이 쓴다.
 * <ul>
 *   <li>선택은 30분 칸 경계여야 한다({@link FreeCells#fromSlots}).</li>
 *   <li>수업과 1분이라도 겹치는 칸은 고를 수 없다(규칙으로 검사 불가, 클라이언트 검증).</li>
 *   <li>매칭 대상 공강(60분 이상 병합 구간)은 {@link Availability#MAX_SLOTS}개 이하.</li>
 * </ul>
 */
public final class FreeSlotValidator {

    private FreeSlotValidator() {
    }

    /** 선택 구간 → 칸 마스크. 30분 경계가 아니거나 형식이 잘못되면 null. */
    public static long[] toCells(List<TimeSlot> selected) {
        try {
            return FreeCells.fromSlots(selected);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 칸 마스크를 현재 수업 기준으로 검사한다. 통과하면 null. */
    public static TimetableFailure validate(long[] cells, List<Course> courses) {
        if (cells == null) {
            return TimetableFailure.of(TimetableFailure.Code.INVALID_FREE_SLOT, "not on the 30-minute grid");
        }
        if (!FreeCells.isSelectable(cells, courseSlots(courses))) {
            return TimetableFailure.of(TimetableFailure.Code.INVALID_FREE_SLOT, "free cell overlaps a course");
        }
        return checkLimit(cells);
    }

    /** 매칭 대상 구간 수 상한 검사. 통과하면 null. */
    public static TimetableFailure checkLimit(long[] cells) {
        int count = FreeCells.matchable(cells).size();
        if (count > Availability.MAX_SLOTS) {
            return TimetableFailure.of(TimetableFailure.Code.TOO_MANY_FREE_SLOTS,
                    count + " > " + Availability.MAX_SLOTS);
        }
        return null;
    }

    /** 수업 목록의 모든 시간대(null 수업·null 칸은 건너뜀). */
    public static List<TimeSlot> courseSlots(List<Course> courses) {
        List<TimeSlot> slots = new ArrayList<>();
        if (courses == null) return slots;
        for (Course c : courses) {
            if (c == null || c.getTimeSlots() == null) continue;
            for (TimeSlot s : c.getTimeSlots()) {
                if (s != null) slots.add(s);
            }
        }
        return slots;
    }
}
