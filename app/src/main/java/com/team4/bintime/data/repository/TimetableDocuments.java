package com.team4.bintime.data.repository;

import com.team4.bintime.data.availability.FreeCells;
import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [BT-015] 시간표 ↔ Firestore 문서 변환. Android/Firebase 의존성이 없어 단위 테스트로 검증한다.
 *
 * <pre>
 * timetables/{uid}                    uid, semester?, revision, freeCells?, updatedAt
 * timetables/{uid}/courses/{courseId} courseId, name, professor?, classroom?, colorCode?,
 *                                     timeSlots[{dayOfWeek, startMinute, endMinute}], createdAt, updatedAt
 * </pre>
 * 타임스탬프(createdAt, updatedAt)는 저장소가 서버 시간으로 채운다. Firestore는 정수를 Long으로 돌려주므로
 * {@code toObject()} 대신 이 변환을 쓴다.
 */
public final class TimetableDocuments {

    public static final String TIMETABLES = "timetables";
    public static final String COURSES = "courses";

    public static final String F_UID = "uid";
    public static final String F_SEMESTER = "semester";
    public static final String F_REVISION = "revision";
    /** [BT-016] 공강 선택 원본. 요일(월~일)별 48칸 비트마스크 정수 7개. {@link FreeCells} 참고. */
    public static final String F_FREE_CELLS = "freeCells";
    public static final String F_CREATED_AT = "createdAt";
    public static final String F_UPDATED_AT = "updatedAt";

    public static final String F_COURSE_ID = "courseId";
    public static final String F_NAME = "name";
    public static final String F_PROFESSOR = "professor";
    public static final String F_CLASSROOM = "classroom";
    public static final String F_COLOR_CODE = "colorCode";
    public static final String F_TIME_SLOTS = "timeSlots";
    public static final String F_DAY_OF_WEEK = "dayOfWeek";
    public static final String F_START_MINUTE = "startMinute";
    public static final String F_END_MINUTE = "endMinute";

    private TimetableDocuments() {
    }

    public static String timetablePath(String uid) {
        return TIMETABLES + "/" + uid;
    }

    public static String coursePath(String uid, String courseId) {
        return timetablePath(uid) + "/" + COURSES + "/" + courseId;
    }

    /** timetables/{uid} 문서(updatedAt 제외). semester는 null 허용이며 null이면 필드를 넣지 않는다. */
    public static Map<String, Object> toTimetableDocument(String uid, String semester, int revision) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(F_UID, uid);
        if (semester != null) doc.put(F_SEMESTER, semester);
        doc.put(F_REVISION, revision);
        return doc;
    }

    /** courses/{courseId} 문서(createdAt, updatedAt 제외). null인 선택 필드는 넣지 않는다. */
    public static Map<String, Object> toCourseDocument(Course course) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(F_COURSE_ID, course.getCourseId());
        doc.put(F_NAME, course.getName());
        if (course.getProfessor() != null) doc.put(F_PROFESSOR, course.getProfessor());
        if (course.getClassroom() != null) doc.put(F_CLASSROOM, course.getClassroom());
        if (course.getColorCode() != null) doc.put(F_COLOR_CODE, course.getColorCode());
        List<Map<String, Object>> slots = new ArrayList<>();
        if (course.getTimeSlots() != null) {
            for (TimeSlot s : course.getTimeSlots()) {
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put(F_DAY_OF_WEEK, s.getDayOfWeek());
                slot.put(F_START_MINUTE, s.getStartMinute());
                slot.put(F_END_MINUTE, s.getEndMinute());
                slots.add(slot);
            }
        }
        doc.put(F_TIME_SLOTS, slots);
        return doc;
    }

    /**
     * courses 문서 → Course. 문서 ID를 courseId로 쓴다. 필수 필드가 없거나 시간대가 잘못된 문서는 null
     * (화면을 깨뜨리지 않도록 저장소가 건너뛰고 로그를 남긴다).
     */
    public static Course fromCourseDocument(String docId, Map<String, Object> data) {
        if (docId == null || data == null) return null;
        Object name = data.get(F_NAME);
        if (!(name instanceof String)) return null;
        Object rawSlots = data.get(F_TIME_SLOTS);
        if (!(rawSlots instanceof List)) return null;
        List<TimeSlot> slots = new ArrayList<>();
        for (Object raw : (List<?>) rawSlots) {
            if (!(raw instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) raw;
            Integer day = readInt(m.get(F_DAY_OF_WEEK));
            Integer start = readInt(m.get(F_START_MINUTE));
            Integer end = readInt(m.get(F_END_MINUTE));
            if (day == null || start == null || end == null) return null;
            TimeSlot slot = new TimeSlot(day, start, end);
            if (!CourseValidator.isValidSlot(slot)) return null;
            slots.add(slot);
        }
        if (slots.isEmpty()) return null;
        return new Course(docId, (String) name,
                readString(data.get(F_PROFESSOR)),
                readString(data.get(F_CLASSROOM)),
                readString(data.get(F_COLOR_CODE)),
                slots);
    }

    /**
     * 부모 문서 + 수업 목록 → Timetable. 부모 문서가 없고 수업도 없으면 미등록(null).
     * 부모 문서 없이 수업만 있으면(앱은 항상 부모와 함께 쓰지만 규칙이 강제하지는 않음) revision 0으로 조립한다.
     * [BT-016] 부모 문서의 freeCells를 {@link Timetable#getFreeSlots()}(공강 선택 원본)로 채운다.
     */
    public static Timetable assemble(String uid, Map<String, Object> parent, List<Course> courses) {
        boolean noCourses = courses == null || courses.isEmpty();
        if (parent == null && noCourses) return null;
        String semester = parent == null ? null : readString(parent.get(F_SEMESTER));
        Integer revision = parent == null ? null : readInt(parent.get(F_REVISION));
        Timetable timetable = new Timetable(uid, uid, semester);
        timetable.setRevision(revision == null ? 0 : revision);
        timetable.setFreeSlots(FreeCells.toSlots(readFreeCells(parent)));
        if (!noCourses) {
            for (Course c : courses) {
                if (c != null) timetable.getCourses().add(c);
            }
        }
        return timetable;
    }

    /** 부모 문서의 공강 선택 원본. 필드가 없거나 형식이 깨졌으면 빈 선택. */
    public static long[] readFreeCells(Map<String, Object> parent) {
        long[] cells = parent == null ? null : FreeCells.fromList(parent.get(F_FREE_CELLS));
        return cells == null ? FreeCells.empty() : cells;
    }

    /** Long/Integer만 int로 읽는다. 소수·문자열·int 범위 밖은 null. */
    static Integer readInt(Object value) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) {
            long v = (Long) value;
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) return null;
            return (int) v;
        }
        return null;
    }

    private static String readString(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
