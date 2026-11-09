package com.team4.bintime.data.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-012] 통합 Course(data.model) 단위 테스트.
 * 레거시 timetable.Course 제거 후 기존 CourseTest의 검증 항목을 이전했습니다.
 * (Parcelable 왕복은 android.os.Parcel 스텁 때문에 JVM 단위 테스트 대상이 아닙니다.)
 */
public class CourseTest {

    @Test
    public void createSingle_acceptsShortAndFullDayLabels() {
        Course shortDay = Course.createSingle("A", "R", "월", "10:00", "11:30");
        Course fullDay = Course.createSingle("B", "R", "금요일", "14:00", "15:30");

        assertEquals(1, shortDay.getTimeSlots().get(0).getDayOfWeek());
        assertEquals(600, shortDay.getTimeSlots().get(0).getStartMinute());
        assertEquals(690, shortDay.getTimeSlots().get(0).getEndMinute());
        assertEquals(5, fullDay.getTimeSlots().get(0).getDayOfWeek());
    }

    @Test
    public void createSingle_slotIndicesOn18SlotGrid() {
        TimeSlot slot = Course.createSingle("A", "R", "수", "14:00", "15:30").getTimeSlots().get(0);
        assertEquals(10, slot.getStartSlotIndex());
        assertEquals(13, slot.getEndSlotIndex());

        TimeSlot last = Course.createSingle("B", "R", "수", "17:30", "18:00").getTimeSlots().get(0);
        assertEquals(17, last.getStartSlotIndex());
        assertEquals(18, last.getEndSlotIndex());
    }

    @Test
    public void createSingle_invalidInputProducesNoSlot() {
        assertTrue(Course.createSingle("A", "R", "월", "11:00", "11:00").getTimeSlots().isEmpty());
        assertTrue(Course.createSingle("A", "R", "월", "12:00", "11:00").getTimeSlots().isEmpty());
        assertTrue(Course.createSingle("A", "R", "?", "10:00", "11:00").getTimeSlots().isEmpty());
        assertTrue(Course.createSingle("A", "R", "월", "25:00", "26:00").getTimeSlots().isEmpty());
    }

    @Test
    public void formattedInfo_matchesFigmaSummary() {
        Course course = Course.createSingle("데이터베이스", "공학관 304호", "화", "13:00", "14:30");
        assertEquals("화 13:00 ~ 14:30 · 공학관 304호", course.getFormattedInfo());

        Course noRoom = Course.createSingle("X", null, "화", "13:00", "14:30");
        assertEquals("화 13:00 ~ 14:30 · 강의실 미정", noRoom.getFormattedInfo());
    }

    @Test
    public void constructor_generatesIdAndNormalizesNullSlots() {
        Course course = new Course(null, "A", null, null, null, null);
        assertNotNull(course.getCourseId());
        assertNotNull(course.getTimeSlots());

        course.setTimeSlots(null);
        assertNotNull(course.getTimeSlots());
    }

    @Test
    public void equality_isByCourseIdOnly() {
        Course a = new Course("id-1", "A", null, "R1", null,
                new ArrayList<>(Arrays.asList(new TimeSlot(1, 600, 660))));
        Course sameIdDifferentFields = new Course("id-1", "B", null, "R2", null, null);
        Course sameFieldsDifferentId = new Course("id-2", "A", null, "R1", null,
                new ArrayList<>(Arrays.asList(new TimeSlot(1, 600, 660))));

        assertEquals(a, sameIdDifferentFields);
        assertNotEquals(a, sameFieldsDifferentId);
        assertNotEquals(new Course().getCourseId(), new Course().getCourseId());
    }
}
