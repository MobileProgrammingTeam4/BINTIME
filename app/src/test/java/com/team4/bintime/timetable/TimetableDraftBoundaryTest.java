package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

/**
 * [BT-056] 공강 선택 화면 draft 의 경계값. 기존 TimetableDraftTest·CourseTest 에 없는 경우만 둔다:
 * 선택이 없을 때·수업이 맞닿을 때·하루 전체 수업·겹치는 수업 입력.
 */
public class TimetableDraftBoundaryTest {

    private static final int MON = 0;

    @Test
    public void noSelection_hasNoFreeTime_andNoNextWindow() {
        TimetableDraft draft = new TimetableDraft(Collections.emptyList());
        assertFalse(draft.hasHourOfFreeTime());
        assertNull(draft.nextFreeWindow(LocalDateTime.of(2026, 11, 9, 9, 0)));
    }

    @Test
    public void suggestGaps_backToBackCourses_selectNothing() {
        TimetableDraft draft = new TimetableDraft(Arrays.asList(
                new Course("수업1", "101호", "월", "09:00", "10:30"),
                new Course("수업2", "102호", "월", "10:30", "12:00")));
        draft.suggestGaps();
        for (int slot = 0; slot < 48; slot++) assertFalse("slot " + slot, draft.isFreeSelected(MON, slot));
        assertFalse(draft.hasHourOfFreeTime());
    }

    @Test
    public void suggestGaps_singleCourse_selectsNothingBeforeOrAfterIt() {
        TimetableDraft draft = new TimetableDraft(Collections.singletonList(
                new Course("수업", "101호", "월", "13:00", "14:30")));
        draft.suggestGaps();
        for (int slot = 0; slot < 48; slot++) assertFalse("slot " + slot, draft.isFreeSelected(MON, slot));
    }

    @Test
    public void wholeDayCourse_noCellOfThatDayCanBeSelected() {
        TimetableDraft draft = new TimetableDraft(Collections.singletonList(
                new Course("집중 수업", "101호", "월", "00:00", "24:00")));
        draft.toggleFree(MON, 0);
        draft.toggleFree(MON, 47);
        assertFalse(draft.isFreeSelected(MON, 0));
        assertFalse(draft.isFreeSelected(MON, 47));
        draft.toggleFree(1, 20);
        draft.toggleFree(1, 21);
        assertTrue(draft.isFreeSelected(1, 20));
    }

    @Test
    public void exactlyOneHourBetweenCourses_isSuggestedAndNextWindowIsThatHour() {
        TimetableDraft draft = new TimetableDraft(Arrays.asList(
                new Course("수업1", "101호", "월", "09:00", "10:00"),
                new Course("수업2", "102호", "월", "11:00", "12:00")));
        draft.suggestGaps();
        assertTrue(draft.isFreeSelected(MON, 20));
        assertTrue(draft.isFreeSelected(MON, 21));
        assertTrue(draft.hasHourOfFreeTime());
        TimetableDraft.FreeWindow next = draft.nextFreeWindow(LocalDateTime.of(2026, 11, 9, 8, 0)); // 월요일
        assertNotNull(next);
        assertEquals(20, next.start);
        assertEquals(22, next.end);
    }

    @Test
    public void overlappingCourseInput_isDetected_touchingAtBothEndsIsNot() {
        TimetableDraft draft = new TimetableDraft(Collections.singletonList(
                new Course("수업", "101호", "월", "10:00", "11:00")));
        assertTrue(draft.overlaps(new Course("새 수업", "102호", "월", "09:00", "12:00"), -1));
        assertTrue(draft.overlaps(new Course("새 수업", "102호", "월", "10:59", "11:30"), -1));
        assertFalse(draft.overlaps(new Course("새 수업", "102호", "월", "09:00", "10:00"), -1));
        assertFalse(draft.overlaps(new Course("새 수업", "102호", "월", "11:00", "12:00"), -1));
    }
}
