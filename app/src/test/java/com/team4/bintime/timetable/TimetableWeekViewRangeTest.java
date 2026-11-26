package com.team4.bintime.timetable;

import com.team4.bintime.data.availability.FreeCells;
import com.team4.bintime.data.repository.FreeSlotValidator;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** [BT-016] 주간 시간표 화면의 표시 범위(기본 09:00~21:00)와 40개 상한의 관계. */
public class TimetableWeekViewRangeTest {

    private static Course course(String day, String start, String end) {
        return new Course("수업", "강의실", day, start, end);
    }

    @Test
    public void noCourses_showsNineToTwentyOne() {
        assertArrayEquals(new int[]{18, 42}, TimetableWeekView.visibleSlots(Collections.emptyList()));
        assertArrayEquals(new int[]{18, 42}, TimetableWeekView.visibleSlots(null));
        assertEquals("09:00 ~ 09:30", TimetableWeekView.slotRange(TimetableWeekView.FIRST_DEFAULT_SLOT));
        assertEquals("20:30 ~ 21:00", TimetableWeekView.slotRange(TimetableWeekView.END_DEFAULT_SLOT - 1));
    }

    @Test
    public void coursesInsideDefaultRange_keepDefaultRange() {
        assertArrayEquals(new int[]{18, 42}, TimetableWeekView.visibleSlots(Arrays.asList(
                course("월", "09:00", "10:30"), course("금", "18:00", "21:00"))));
    }

    @Test
    public void courseAfterTwentyOne_extendsToNextHour() {
        assertArrayEquals(new int[]{18, 44}, TimetableWeekView.visibleSlots(
                Collections.singletonList(course("화", "20:00", "21:30"))));
        assertArrayEquals(new int[]{18, 44}, TimetableWeekView.visibleSlots(
                Collections.singletonList(course("화", "20:00", "22:00"))));
    }

    @Test
    public void courseBeforeNine_extendsFromItsHour_andNullRowsAreSkipped() {
        assertArrayEquals(new int[]{16, 42}, TimetableWeekView.visibleSlots(Arrays.asList(
                null, course("수", "08:30", "09:30"))));
    }

    /** 09:00~21:00 안에서 월~금에 가능한 가장 많은 60분 구간(2칸 선택 1칸 비움)은 40개로 상한과 같다. */
    @Test
    public void densestSelectionInDefaultRange_staysWithinFortySlotLimit() {
        long[] cells = new long[FreeCells.DAYS];
        for (int day = 0; day < 5; day++) {
            for (int slot = TimetableWeekView.FIRST_DEFAULT_SLOT; slot + 1 < TimetableWeekView.END_DEFAULT_SLOT; slot += 3) {
                cells[day] |= 3L << slot;
            }
        }
        assertEquals(40, FreeCells.matchable(cells).size());
        assertNull(FreeSlotValidator.checkLimit(cells));

        // 범위 밖 칸이 더해지면(수업 때문에 화면이 넓어진 경우) 기존 상한 검사가 막는다.
        cells[0] |= 3L << 42;
        assertNotNull(FreeSlotValidator.checkLimit(cells));
    }
}
