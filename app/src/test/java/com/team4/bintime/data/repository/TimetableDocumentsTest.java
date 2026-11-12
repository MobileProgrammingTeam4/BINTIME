package com.team4.bintime.data.repository;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-015] TimetableDocuments — Course/Timetable ↔ Firestore 문서 맵 변환.
 */
public class TimetableDocumentsTest {

    @Test
    public void paths() {
        assertEquals("timetables/u1", TimetableDocuments.timetablePath("u1"));
        assertEquals("timetables/u1/courses/c1", TimetableDocuments.coursePath("u1", "c1"));
    }

    @Test
    public void timetableDocument_fields() {
        Map<String, Object> doc = TimetableDocuments.toTimetableDocument("u1", "2026년 2학기", 3);
        assertEquals("u1", doc.get("uid"));
        assertEquals("2026년 2학기", doc.get("semester"));
        assertEquals(3, doc.get("revision"));
        assertEquals(3, doc.size());
    }

    @Test
    public void timetableDocument_nullSemesterOmitted() {
        Map<String, Object> doc = TimetableDocuments.toTimetableDocument("u1", null, 1);
        assertFalse(doc.containsKey("semester"));
        assertEquals(2, doc.size());
    }

    @Test
    public void assemble_missingOrNullSemester_isNull() {
        Map<String, Object> parent = new HashMap<>();
        parent.put("uid", "u1");
        parent.put("revision", 2L);
        assertNull(TimetableDocuments.assemble("u1", parent, Collections.emptyList()).getSemester());
        parent.put("semester", null);
        assertNull(TimetableDocuments.assemble("u1", parent, Collections.emptyList()).getSemester());
    }

    @Test
    public void courseDocument_allFields() {
        Course c = new Course("c1", "자료구조", "김교수", "공학관 301호", "#E0F0FF",
                new ArrayList<>(Arrays.asList(new TimeSlot(1, 600, 690), new TimeSlot(3, 615, 700))));
        Map<String, Object> doc = TimetableDocuments.toCourseDocument(c);
        assertEquals("c1", doc.get("courseId"));
        assertEquals("자료구조", doc.get("name"));
        assertEquals("김교수", doc.get("professor"));
        assertEquals("공학관 301호", doc.get("classroom"));
        assertEquals("#E0F0FF", doc.get("colorCode"));
        List<?> slots = (List<?>) doc.get("timeSlots");
        assertEquals(2, slots.size());
        Map<?, ?> second = (Map<?, ?>) slots.get(1);
        assertEquals(3, second.get("dayOfWeek"));
        assertEquals(615, second.get("startMinute"));
        assertEquals(700, second.get("endMinute"));
        assertFalse(doc.containsKey("createdAt"));
        assertFalse(doc.containsKey("updatedAt"));
    }

    @Test
    public void courseDocument_nullOptionalFieldsOmitted() {
        Course c = new Course("c1", "n", null, null, null,
                new ArrayList<>(Collections.singletonList(new TimeSlot(1, 600, 660))));
        Map<String, Object> doc = TimetableDocuments.toCourseDocument(c);
        assertFalse(doc.containsKey("professor"));
        assertFalse(doc.containsKey("classroom"));
        assertFalse(doc.containsKey("colorCode"));
        assertEquals(3, doc.size());
    }

    /** Firestore는 정수를 Long으로 돌려준다. */
    @Test
    public void fromCourseDocument_longValues_roundTrip() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("courseId", "c1");
        doc.put("name", "자료구조");
        doc.put("classroom", "공학관");
        doc.put("timeSlots", Arrays.asList(slotMap(1L, 0L, 60L), slotMap(7L, 1380L, 1440L)));
        Course c = TimetableDocuments.fromCourseDocument("c1", doc);
        assertEquals("c1", c.getCourseId());
        assertEquals("자료구조", c.getName());
        assertNull(c.getProfessor());
        assertEquals("공학관", c.getClassroom());
        assertEquals(Arrays.asList(new TimeSlot(1, 0, 60), new TimeSlot(7, 1380, 1440)), c.getTimeSlots());
    }

    @Test
    public void roundTrip_toAndFrom() {
        Course original = new Course("c9", "운영체제", "이교수", "공학관 301호", "#E0FFE0",
                new ArrayList<>(Arrays.asList(new TimeSlot(2, 540, 630), new TimeSlot(4, 540, 630))));
        Course back = TimetableDocuments.fromCourseDocument("c9", TimetableDocuments.toCourseDocument(original));
        assertEquals(original.getName(), back.getName());
        assertEquals(original.getProfessor(), back.getProfessor());
        assertEquals(original.getClassroom(), back.getClassroom());
        assertEquals(original.getColorCode(), back.getColorCode());
        assertEquals(original.getTimeSlots(), back.getTimeSlots());
    }

    @Test
    public void fromCourseDocument_docIdWins() {
        Map<String, Object> doc = validDoc();
        doc.put("courseId", "other");
        assertEquals("c1", TimetableDocuments.fromCourseDocument("c1", doc).getCourseId());
    }

    @Test
    public void fromCourseDocument_brokenDocs_null() {
        assertNull(TimetableDocuments.fromCourseDocument("c1", null));
        assertNull(TimetableDocuments.fromCourseDocument(null, validDoc()));

        Map<String, Object> noName = validDoc();
        noName.remove("name");
        assertNull(TimetableDocuments.fromCourseDocument("c1", noName));

        Map<String, Object> noSlots = validDoc();
        noSlots.remove("timeSlots");
        assertNull(TimetableDocuments.fromCourseDocument("c1", noSlots));

        Map<String, Object> emptySlots = validDoc();
        emptySlots.put("timeSlots", Collections.emptyList());
        assertNull(TimetableDocuments.fromCourseDocument("c1", emptySlots));

        Map<String, Object> doubleValue = validDoc();
        doubleValue.put("timeSlots", Collections.singletonList(slotMap(1L, 600.0, 660L)));
        assertNull(TimetableDocuments.fromCourseDocument("c1", doubleValue));

        Map<String, Object> outOfRange = validDoc();
        outOfRange.put("timeSlots", Collections.singletonList(slotMap(1L, 600L, 1441L)));
        assertNull(TimetableDocuments.fromCourseDocument("c1", outOfRange));

        Map<String, Object> hugeLong = validDoc();
        hugeLong.put("timeSlots", Collections.singletonList(slotMap(1L, 600L, 4294967956L)));
        assertNull(TimetableDocuments.fromCourseDocument("c1", hugeLong));

        Map<String, Object> notMap = validDoc();
        notMap.put("timeSlots", Collections.singletonList("월 10:00"));
        assertNull(TimetableDocuments.fromCourseDocument("c1", notMap));
    }

    @Test
    public void fromCourseDocument_nonStringOptional_ignored() {
        Map<String, Object> doc = validDoc();
        doc.put("professor", 123L);
        assertNull(TimetableDocuments.fromCourseDocument("c1", doc).getProfessor());
    }

    @Test
    public void assemble_notRegistered_null() {
        assertNull(TimetableDocuments.assemble("u1", null, null));
        assertNull(TimetableDocuments.assemble("u1", null, Collections.emptyList()));
    }

    @Test
    public void assemble_parentAndCourses() {
        Map<String, Object> parent = new HashMap<>();
        parent.put("uid", "u1");
        parent.put("semester", "2026년 2학기");
        parent.put("revision", 5L);
        Course c = TimetableDocuments.fromCourseDocument("c1", validDoc());
        Timetable t = TimetableDocuments.assemble("u1", parent, Arrays.asList(c, null));
        assertEquals("u1", t.getUserId());
        assertEquals("u1", t.getTimetableId());
        assertEquals("2026년 2학기", t.getSemester());
        assertEquals(5, t.getRevision());
        assertEquals(1, t.getCourses().size());
        assertTrue(t.getFreeSlots().isEmpty());
    }

    @Test
    public void assemble_parentWithoutCourses_emptyTimetable() {
        Map<String, Object> parent = new HashMap<>();
        parent.put("revision", 2L);
        Timetable t = TimetableDocuments.assemble("u1", parent, null);
        assertEquals(2, t.getRevision());
        assertEquals(0, t.getCourseCount());
    }

    @Test
    public void assemble_coursesWithoutParent_revision0() {
        Course c = TimetableDocuments.fromCourseDocument("c1", validDoc());
        Timetable t = TimetableDocuments.assemble("u1", null, Collections.singletonList(c));
        assertEquals(0, t.getRevision());
        assertEquals(1, t.getCourseCount());
    }

    @Test
    public void readInt_types() {
        assertEquals(Integer.valueOf(5), TimetableDocuments.readInt(5));
        assertEquals(Integer.valueOf(5), TimetableDocuments.readInt(5L));
        assertNull(TimetableDocuments.readInt(5.0));
        assertNull(TimetableDocuments.readInt("5"));
        assertNull(TimetableDocuments.readInt(null));
        assertNull(TimetableDocuments.readInt((long) Integer.MAX_VALUE + 1));
    }

    private static Map<String, Object> validDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("courseId", "c1");
        doc.put("name", "자료구조");
        doc.put("timeSlots", new ArrayList<>(Collections.singletonList(slotMap(1L, 600L, 690L))));
        return doc;
    }

    private static Map<String, Object> slotMap(Object day, Object start, Object end) {
        Map<String, Object> m = new HashMap<>();
        m.put("dayOfWeek", day);
        m.put("startMinute", start);
        m.put("endMinute", end);
        return m;
    }
}
