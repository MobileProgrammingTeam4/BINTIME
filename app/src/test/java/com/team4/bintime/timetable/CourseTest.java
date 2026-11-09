package com.team4.bintime.timetable;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-005 / BT-006] 시간표 및 수업 시각·요일 변환, 슬롯 계산 단위 테스트
 */
public class CourseTest {

    @Test
    public void testFormat24Hour_amPmConversion() {
        // 오전 테스트
        assertEquals("00:00", Course.format24Hour(false, 12, 0));
        assertEquals("01:30", Course.format24Hour(false, 1, 30));
        assertEquals("10:00", Course.format24Hour(false, 10, 0));
        assertEquals("11:45", Course.format24Hour(false, 11, 45));

        // 오후 테스트 (피드백 요구: 오후 2시 -> 14:00)
        assertEquals("12:00", Course.format24Hour(true, 12, 0));
        assertEquals("13:00", Course.format24Hour(true, 1, 0));
        assertEquals("14:00", Course.format24Hour(true, 2, 0));
        assertEquals("18:30", Course.format24Hour(true, 6, 30));
        assertEquals("23:55", Course.format24Hour(true, 11, 55));
    }

    @Test
    public void testCourseSlotCalculation_30MinuteIntervals() {
        Course course1 = new Course("컴퓨터 구조론", "공학관 302호", "월", "10:00", "11:30");
        // 9시 기준: 10:00 -> slot 2, 11:30 -> slot 5
        assertEquals(2, course1.getStartSlotIndex(9));
        assertEquals(5, course1.getEndSlotIndex(9));

        Course course2 = new Course("오전 9시 수업", "IT관", "화", "09:00", "09:30");
        assertEquals(0, course2.getStartSlotIndex(9));
        assertEquals(1, course2.getEndSlotIndex(9));

        Course course3 = new Course("오후 2시 수업", "공학관", "수", "14:00", "15:30");
        assertEquals(10, course3.getStartSlotIndex(9));
        assertEquals(13, course3.getEndSlotIndex(9));
    }

    @Test
    public void testSlotTimeRange_30MinuteSlots() {
        assertEquals("09:00 ~ 09:30", TimetableRegistrationActivity.getSlotTimeRange(0));
        assertEquals("09:30 ~ 10:00", TimetableRegistrationActivity.getSlotTimeRange(1));
        assertEquals("10:00 ~ 10:30", TimetableRegistrationActivity.getSlotTimeRange(2));
        assertEquals("14:00 ~ 14:30", TimetableRegistrationActivity.getSlotTimeRange(10));
        assertEquals("17:30 ~ 18:00", TimetableRegistrationActivity.getSlotTimeRange(17));
    }

    @Test
    public void testCourseParseAndFormat() {
        String title = "데이터베이스";
        String info = "화 13:00 ~ 14:30 · 공학관 304호";

        Course course = Course.parse(title, info);
        assertNotNull(course);
        assertEquals("데이터베이스", course.getName());
        assertEquals("화", course.getShortDay());
        assertEquals("13:00", course.getStartTime());
        assertEquals("14:30", course.getEndTime());
        assertEquals("공학관 304호", course.getClassroom());

        assertEquals("화 13:00 ~ 14:30 · 공학관 304호", course.getFormattedInfo());
    }

    @Test
    public void testDayIndex() {
        assertEquals(0, new Course("", "", "월요일", "", "").getDayIndex());
        assertEquals(1, new Course("", "", "화", "", "").getDayIndex());
        assertEquals(2, new Course("", "", "수요일", "", "").getDayIndex());
        assertEquals(3, new Course("", "", "목", "", "").getDayIndex());
        assertEquals(4, new Course("", "", "금요일", "", "").getDayIndex());
        assertEquals(-1, new Course("", "", "토", "", "").getDayIndex());
        assertEquals(-1, new Course("", "", "일요일", "", "").getDayIndex());
    }

    @Test
    public void testFreeSelectionRespectsAdjacentSlotsAndCourseBoundaries() {
        Course first = new Course("수학", "101호", "월", "10:00", "10:30");
        Course next = new Course("물리", "102호", "월", "11:30", "12:00");
        TimetableDraft draft = new TimetableDraft(Arrays.asList(first, next));
        draft.suggestGaps();
        assertFalse(draft.isFreeSelected(0, 20));
        assertTrue(draft.isFreeSelected(0, 21));
        assertTrue(draft.isFreeSelected(0, 22));
        assertFalse(draft.isFreeSelected(0, 23));
        assertTrue(draft.hasHourOfFreeTime());

        draft.toggleFree(0, 21);
        draft.toggleFree(0, 20);
        draft.suggestGaps();
        assertFalse(draft.isFreeSelected(0, 20));
        assertFalse(draft.isFreeSelected(0, 21));
        assertTrue(draft.isFreeSelected(0, 22));
        assertFalse(draft.hasHourOfFreeTime());
    }

    @Test
    public void testCourseEditsRespectWeekdaysAndAllowAdjacentClasses() {
        Course original = new Course("수학", "101호", "월", "10:00", "11:00");
        TimetableDraft draft = new TimetableDraft(Collections.singletonList(original));
        assertTrue(draft.overlaps(new Course("물리", "102호", "월", "10:30", "11:30"), -1));
        assertFalse(draft.overlaps(new Course("물리", "102호", "월", "11:00", "11:30"), -1));
        assertFalse(draft.overlaps(new Course("물리", "102호", "화", "10:30", "11:30"), -1));
        assertFalse(draft.overlaps(original, 0));
    }

    @Test
    public void testMatchingCardUsesCurrentOrNextHourOfSelectedFreeTime() {
        TimetableDraft draft = new TimetableDraft(Collections.emptyList());
        draft.toggleFree(0, 28);
        draft.toggleFree(0, 29);
        draft.toggleFree(1, 20);
        draft.toggleFree(1, 21);
        LocalDate monday = LocalDate.of(2026, 11, 9);
        TimetableDraft.FreeWindow current = draft.nextFreeWindow(LocalDateTime.of(2026, 11, 9, 14, 30));
        assertEquals(monday, current.date);
        assertEquals(28, current.start);
        assertEquals(30, current.end);
        TimetableDraft.FreeWindow next = draft.nextFreeWindow(LocalDateTime.of(2026, 11, 9, 15, 0));
        assertEquals(monday.plusDays(1), next.date);
        assertEquals(20, next.start);
        assertEquals(22, next.end);
    }
}
