package com.team4.bintime.data.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-012] data.model.Course ↔ develop timetable.Course(Serializable, 문자열 시간) 변환 테스트.
 * develop의 timetable.Course는 수정하지 않고 공개 API만 사용합니다.
 */
public class CourseLegacyConversionTest {

    private static com.team4.bintime.timetable.Course legacy(String day, String start, String end) {
        return new com.team4.bintime.timetable.Course("알고리즘", "공학관 303호", day, start, end);
    }

    @Test
    public void fromLegacy_mapsNameRoomDayAndMinutes() {
        Course c = Course.fromLegacy(legacy("목", "13:00", "14:30"));

        assertNotNull(c);
        assertEquals("알고리즘", c.getName());
        assertEquals("공학관 303호", c.getClassroom());
        assertEquals(1, c.getTimeSlots().size());
        assertEquals(new TimeSlot(4, 780, 870), c.getTimeSlots().get(0));
        assertNull(c.getProfessor());
        assertNull(c.getColorCode());
    }

    @Test
    public void fromLegacy_acceptsFullDayLabelAndWeekend() {
        assertEquals(1, Course.fromLegacy(legacy("월요일", "09:00", "10:00")).getTimeSlots().get(0).getDayOfWeek());
        assertEquals(6, Course.fromLegacy(legacy("토", "09:00", "10:00")).getTimeSlots().get(0).getDayOfWeek());
        assertEquals(7, Course.fromLegacy(legacy("일요일", "09:00", "10:00")).getTimeSlots().get(0).getDayOfWeek());
    }

    @Test
    public void fromLegacy_invalidTimeOrDay_producesNoSlot() {
        assertTrue(Course.fromLegacy(legacy("월", "11:00", "10:00")).getTimeSlots().isEmpty());
        assertTrue(Course.fromLegacy(legacy("월", "10:00", "10:00")).getTimeSlots().isEmpty());
        assertTrue(Course.fromLegacy(legacy("월", "", "10:00")).getTimeSlots().isEmpty());
        assertTrue(Course.fromLegacy(legacy("월", null, null)).getTimeSlots().isEmpty());
        assertTrue(Course.fromLegacy(legacy("X", "09:00", "10:00")).getTimeSlots().isEmpty());
        assertTrue(Course.fromLegacy(legacy(null, "09:00", "10:00")).getTimeSlots().isEmpty());
    }

    @Test
    public void fromLegacy_null_returnsNull() {
        assertNull(Course.fromLegacy(null));
    }

    @Test
    public void fromLegacy_generatesNewIdEachCall() {
        com.team4.bintime.timetable.Course src = legacy("화", "10:00", "11:00");
        assertNotEquals(Course.fromLegacy(src).getCourseId(), Course.fromLegacy(src).getCourseId());
    }

    @Test
    public void toLegacy_mapsFirstSlotToShortDayAndHhmm() {
        Course c = Course.createSingle("인공지능", "사회관 201호", "금요일", "09:05", "12:00");
        com.team4.bintime.timetable.Course l = c.toLegacy();

        assertEquals("인공지능", l.getName());
        assertEquals("사회관 201호", l.getClassroom());
        assertEquals("금", l.getDay());
        assertEquals("09:05", l.getStartTime());
        assertEquals("12:00", l.getEndTime());
        assertEquals(545, l.getStartMinute());
        assertEquals(720, l.getEndMinute());
    }

    @Test
    public void toLegacy_multipleSlots_onlyFirstIsConverted() {
        Course c = new Course("id", "운영체제", null, "R", null, new ArrayList<>(Arrays.asList(
                new TimeSlot(1, 600, 690), new TimeSlot(3, 600, 690))));
        com.team4.bintime.timetable.Course l = c.toLegacy();

        assertEquals("월", l.getDay());
        assertEquals("10:00", l.getStartTime());
        assertEquals("11:30", l.getEndTime());
    }

    @Test
    public void toLegacy_noSlots_leavesTimeFieldsNull() {
        com.team4.bintime.timetable.Course l = new Course("id", "미정", null, "R", null, null).toLegacy();

        assertNotNull(l);
        assertEquals("미정", l.getName());
        assertNull(l.getDay());
        assertNull(l.getStartTime());
        assertNull(l.getEndTime());
    }

    @Test
    public void roundTrip_preservesLegacyFieldsButNotId() {
        com.team4.bintime.timetable.Course src = legacy("수", "13:00", "14:30");
        Course model = Course.fromLegacy(src);
        com.team4.bintime.timetable.Course back = model.toLegacy();

        assertEquals(src, back); // legacy equals: name, classroom, day, start, end

        Course again = Course.fromLegacy(back);
        assertEquals(model.getTimeSlots(), again.getTimeSlots());
        assertNotEquals(model.getCourseId(), again.getCourseId());
    }

    @Test
    public void roundTrip_fullDayLabelNormalizesToShort() {
        com.team4.bintime.timetable.Course back = Course.fromLegacy(legacy("수요일", "13:00", "14:30")).toLegacy();
        assertEquals("수", back.getDay());
    }
}
