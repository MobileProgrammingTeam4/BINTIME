package com.team4.bintime.data.model;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-012] Timetable · Course(data.model) 단위 테스트.
 */
public class TimetableTest {

    private Timetable timetable;

    @Before
    public void setUp() {
        timetable = new Timetable("tt_test", "user_test", "2026년 2학기");
    }

    // ── Course 생성 및 변환 ─────────────────────────────

    @Test
    public void testCreateSingleCourse() {
        Course course = Course.createSingle("운영체제", "공학관 301호", "수", "09:00", "10:30");
        assertNotNull(course);
        assertEquals("운영체제", course.getName());
        assertEquals("공학관 301호", course.getClassroom());
        assertEquals(1, course.getTimeSlots().size());

        TimeSlot slot = course.getTimeSlots().get(0);
        assertEquals(3, slot.getDayOfWeek()); // 수 = 3
        assertEquals(540, slot.getStartMinute());
        assertEquals(630, slot.getEndMinute());
    }

    @Test
    public void testCourseFormattedInfo() {
        Course course = Course.createSingle("데이터베이스", "공학관 304호", "화", "13:00", "14:30");
        assertEquals("화 13:00 ~ 14:30 · 공학관 304호", course.getFormattedInfo());
    }

    // ── Timetable CRUD ─────────────────────────────────

    @Test
    public void testAddCourse() {
        Course c1 = Course.createSingle("운영체제", "공학관", "수", "09:00", "10:00");
        timetable.addOrUpdateCourse(c1);
        assertEquals(1, timetable.getCourseCount());
        assertEquals(2, timetable.getRevision()); // 1 (초기) + 1 (추가)
    }

    @Test
    public void testUpdateCourse() {
        Course c1 = Course.createSingle("운영체제", "공학관", "수", "09:00", "10:00");
        timetable.addOrUpdateCourse(c1);

        c1.setClassroom("인문관");
        timetable.addOrUpdateCourse(c1);
        assertEquals(1, timetable.getCourseCount()); // 교체, 추가 아님
        assertEquals("인문관", timetable.findCourseById(c1.getCourseId()).getClassroom());
    }

    @Test
    public void testRemoveCourse() {
        Course c1 = Course.createSingle("운영체제", "공학관", "수", "09:00", "10:00");
        timetable.addOrUpdateCourse(c1);
        assertTrue(timetable.removeCourse(c1.getCourseId()));
        assertEquals(0, timetable.getCourseCount());
        assertNull(timetable.findCourseById(c1.getCourseId()));
    }

    @Test
    public void testRemoveNonExistentCourse() {
        assertFalse(timetable.removeCourse("non_existent_id"));
    }

    @Test
    public void testFindCourseById() {
        Course c1 = Course.createSingle("운영체제", "공학관", "수", "09:00", "10:00");
        timetable.addOrUpdateCourse(c1);
        assertNotNull(timetable.findCourseById(c1.getCourseId()));
        assertNull(timetable.findCourseById("unknown"));
    }

    // ── 요일별 슬롯 조회 ────────────────────────────────

    @Test
    public void testGetCourseSlotsForDay() {
        Course c1 = Course.createSingle("운영체제", "공학관", "수", "09:00", "10:00");
        Course c2 = Course.createSingle("알고리즘", "공학관", "수", "13:00", "14:30");
        Course c3 = Course.createSingle("인공지능", "사회관", "금", "10:00", "12:00");
        timetable.addOrUpdateCourse(c1);
        timetable.addOrUpdateCourse(c2);
        timetable.addOrUpdateCourse(c3);

        assertEquals(2, timetable.getCourseSlotsForDay(3).size()); // 수요일 2과목
        assertEquals(1, timetable.getCourseSlotsForDay(5).size()); // 금요일 1과목
        assertEquals(0, timetable.getCourseSlotsForDay(1).size()); // 월요일 0과목
    }

    @Test
    public void testGetFreeSlotsForDay() {
        timetable.setFreeSlots(Arrays.asList(
                new TimeSlot(3, 660, 720),  // 수 11:00~12:00
                new TimeSlot(3, 720, 780),  // 수 12:00~13:00
                new TimeSlot(4, 660, 720)   // 목 11:00~12:00
        ));

        assertEquals(2, timetable.getFreeSlotsForDay(3).size()); // 수요일 공강 2슬롯
        assertEquals(1, timetable.getFreeSlotsForDay(4).size()); // 목요일 공강 1슬롯
        assertEquals(0, timetable.getFreeSlotsForDay(1).size()); // 월요일 공강 없음
    }

    // ── Revision ────────────────────────────────────────

    @Test
    public void testRevisionIncrement() {
        assertEquals(1, timetable.getRevision());
        timetable.incrementRevision();
        assertEquals(2, timetable.getRevision());
    }
}
