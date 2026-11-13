package com.team4.bintime.data.repository;

import com.team4.bintime.data.availability.Availability;
import com.team4.bintime.data.availability.FreeCells;
import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * [BT-016] FreeSlotValidator — 30분 경계, 수업 겹침, 매칭 대상 40개 상한.
 */
public class FreeSlotValidatorTest {

    private static final int MON = 1;

    private static Course course(String id, TimeSlot... slots) {
        return new Course(id, "수업" + id, null, null, null, new ArrayList<>(Arrays.asList(slots)));
    }

    private static void assertCode(TimetableFailure.Code expected, TimetableFailure failure) {
        assertNotNull("expected " + expected, failure);
        assertEquals(expected, failure.getCode());
    }

    @Test
    public void offGridSelection_invalid() {
        assertNull(FreeSlotValidator.toCells(Collections.singletonList(new TimeSlot(MON, 545, 600))));
        assertCode(TimetableFailure.Code.INVALID_FREE_SLOT, FreeSlotValidator.validate(null, null));
    }

    @Test
    public void selectionOverlappingCourse_invalid() {
        long[] cells = FreeSlotValidator.toCells(Collections.singletonList(new TimeSlot(MON, 540, 660)));
        List<Course> courses = Collections.singletonList(course("a", new TimeSlot(MON, 630, 690)));
        assertCode(TimetableFailure.Code.INVALID_FREE_SLOT, FreeSlotValidator.validate(cells, courses));
    }

    @Test
    public void selectionTouchingCourse_ok_andNullCoursesSkipped() {
        long[] cells = FreeSlotValidator.toCells(Collections.singletonList(new TimeSlot(MON, 540, 630)));
        List<Course> courses = Arrays.asList(null, course("a", new TimeSlot(MON, 630, 690)));
        assertNull(FreeSlotValidator.validate(cells, courses));
        assertNull(FreeSlotValidator.validate(FreeCells.empty(), null));
    }

    @Test
    public void limit_40ok_41rejected() {
        assertNull(FreeSlotValidator.checkLimit(cellsWithBlocks(Availability.MAX_SLOTS)));
        assertCode(TimetableFailure.Code.TOO_MANY_FREE_SLOTS,
                FreeSlotValidator.checkLimit(cellsWithBlocks(Availability.MAX_SLOTS + 1)));
    }

    @Test
    public void limit_countsOnlyMatchableBlocks() {
        // 30분 단독 칸은 매칭 대상이 아니므로 개수에 들어가지 않는다.
        long[] cells = cellsWithBlocks(Availability.MAX_SLOTS);
        cells[6] |= 1L << 47; // 일 23:30 단독 칸
        assertNull(FreeSlotValidator.checkLimit(cells));
    }

    @Test
    public void limit_bridgingCellMerges41BlocksTo40_ok() {
        // [BT-036] 41개 상태에서 두 블록 사이 30분 칸을 채우면 하나로 합쳐져 40개가 된다.
        long[] cells = cellsWithBlocks(Availability.MAX_SLOTS + 1);
        cells[0] |= 1L << 2; // 월 칸 0~1, 3~4 사이
        assertEquals(Availability.MAX_SLOTS, FreeCells.matchable(cells).size());
        assertNull(FreeSlotValidator.checkLimit(cells));
    }

    /** 60분 블록 n개(하루 최대 16개, 블록 사이 30분 간격). */
    private static long[] cellsWithBlocks(int n) {
        long[] cells = FreeCells.empty();
        int made = 0;
        for (int d = 0; d < 7 && made < n; d++) {
            for (int cell = 0; cell + 1 < 48 && made < n; cell += 3) {
                cells[d] |= 3L << cell;
                made++;
            }
        }
        assertEquals(n, FreeCells.matchable(cells).size());
        return cells;
    }
}
