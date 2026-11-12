package com.team4.bintime.data.repository;

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
import static org.junit.Assert.assertSame;

/**
 * [BT-015] CourseValidator — 필드 검증, 같은 요일 겹침, 일괄 검증.
 */
public class CourseValidatorTest {

    private static final int MON = 1;
    private static final int TUE = 2;

    private static Course course(String id, TimeSlot... slots) {
        return new Course(id, "수업" + id, null, null, null, new ArrayList<>(Arrays.asList(slots)));
    }

    private static TimeSlot slot(int day, int start, int end) {
        return new TimeSlot(day, start, end);
    }

    private static void assertCode(TimetableFailure.Code expected, TimetableFailure failure) {
        assertNotNull("expected " + expected, failure);
        assertEquals(expected, failure.getCode());
    }

    // ── 필드 ─────────────────────────────────────────

    @Test
    public void validCourse_passes() {
        Course c = new Course("c1", "자료구조", "김교수", "공학관 301호", "#E0F0FF",
                new ArrayList<>(Collections.singletonList(slot(MON, 600, 690))));
        assertNull(CourseValidator.validateFields(c));
    }

    @Test
    public void nullCourse_invalid() {
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(null));
    }

    @Test
    public void courseId_invalidValues() {
        for (String id : new String[]{" ", "a/b", ".", ".."}) {
            Course c = course("x", slot(MON, 600, 660));
            c.setCourseId(id);
            assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
        }
        Course nullId = course("x", slot(MON, 600, 660));
        nullId.setCourseId(null);
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(nullId));
        Course longId = course("x", slot(MON, 600, 660));
        longId.setCourseId(repeat('a', 129));
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(longId));
    }

    @Test
    public void name_length1to30() {
        Course c = course("c", slot(MON, 600, 660));
        c.setName("   ");
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
        c.setName(null);
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
        c.setName(repeat('가', 30));
        assertNull(CourseValidator.validateFields(c));
        c.setName(repeat('가', 31));
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
    }

    @Test
    public void professorAndClassroom_max30() {
        Course c = course("c", slot(MON, 600, 660));
        c.setProfessor(repeat('a', 30));
        c.setClassroom(repeat('b', 30));
        assertNull(CourseValidator.validateFields(c));
        c.setProfessor(repeat('a', 31));
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
        c.setProfessor(null);
        c.setClassroom(repeat('b', 31));
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
    }

    @Test
    public void colorCode_format() {
        Course c = course("c", slot(MON, 600, 660));
        for (String ok : new String[]{"#FFE0E0", "#abcdef"}) {
            c.setColorCode(ok);
            assertNull(CourseValidator.validateFields(c));
        }
        for (String bad : new String[]{"FFE0E0", "#FFF", "#GGGGGG", "#FFE0E0FF"}) {
            c.setColorCode(bad);
            assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
        }
    }

    @Test
    public void timeSlots_count1to7() {
        Course none = course("c");
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(none));
        List<TimeSlot> seven = new ArrayList<>();
        for (int d = 1; d <= 7; d++) seven.add(slot(d, 600, 660));
        Course c = new Course("c", "n", null, null, null, seven);
        assertNull(CourseValidator.validateFields(c));
        List<TimeSlot> eight = new ArrayList<>(seven);
        eight.add(slot(MON, 700, 760));
        c.setTimeSlots(eight);
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
    }

    @Test
    public void timeSlot_ranges() {
        assertNull(CourseValidator.validateFields(course("c", slot(MON, 0, 1440))));
        assertNull(CourseValidator.validateFields(course("c", slot(7, 615, 700))));   // 30분 단위 아니어도 됨
        TimeSlot[] bad = {
                slot(0, 600, 660), slot(8, 600, 660),
                slot(MON, -1, 60), slot(MON, 600, 600), slot(MON, 660, 600), slot(MON, 1400, 1441)};
        for (TimeSlot s : bad) {
            assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(course("c", s)));
        }
        Course withNull = new Course("c", "n", null, null, null, new ArrayList<>(Collections.singletonList((TimeSlot) null)));
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(withNull));
    }

    @Test
    public void ownSlots_overlapRejected_touchingAllowed() {
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(
                course("c", slot(MON, 600, 690), slot(MON, 660, 720))));
        assertNull(CourseValidator.validateFields(course("c", slot(MON, 600, 660), slot(MON, 660, 720))));
        assertNull(CourseValidator.validateFields(course("c", slot(MON, 600, 690), slot(TUE, 600, 690))));
    }

    // ── 겹침 ─────────────────────────────────────────

    @Test
    public void overlap_sameDay_rejectedWithConflictId() {
        List<Course> existing = Collections.singletonList(course("a", slot(MON, 600, 690)));
        TimetableFailure f = CourseValidator.validate(course("b", slot(MON, 660, 720)), existing);
        assertCode(TimetableFailure.Code.OVERLAP, f);
        assertEquals("a", f.getConflictingCourseId());
    }

    @Test
    public void overlap_oneMinute_rejected() {
        List<Course> existing = Collections.singletonList(course("a", slot(MON, 600, 661)));
        assertCode(TimetableFailure.Code.OVERLAP,
                CourseValidator.validate(course("b", slot(MON, 660, 720)), existing));
    }

    @Test
    public void touching_endEqualsStart_allowed() {
        List<Course> existing = Collections.singletonList(course("a", slot(MON, 600, 660)));
        assertNull(CourseValidator.validate(course("b", slot(MON, 660, 720)), existing));
        assertNull(CourseValidator.validate(course("c", slot(MON, 540, 600)), existing));
    }

    @Test
    public void differentDay_allowed() {
        List<Course> existing = Collections.singletonList(course("a", slot(MON, 600, 690)));
        assertNull(CourseValidator.validate(course("b", slot(TUE, 600, 690)), existing));
    }

    @Test
    public void containedAndIdentical_rejected() {
        List<Course> existing = Collections.singletonList(course("a", slot(MON, 540, 720)));
        assertCode(TimetableFailure.Code.OVERLAP,
                CourseValidator.validate(course("b", slot(MON, 600, 630)), existing));
        assertCode(TimetableFailure.Code.OVERLAP,
                CourseValidator.validate(course("c", slot(MON, 540, 720)), existing));
    }

    @Test
    public void update_sameCourseIdExcluded() {
        List<Course> existing = Arrays.asList(course("a", slot(MON, 600, 690)), course("b", slot(MON, 780, 840)));
        assertNull(CourseValidator.validate(course("a", slot(MON, 630, 720)), existing));
        TimetableFailure f = CourseValidator.validate(course("a", slot(MON, 630, 800)), existing);
        assertCode(TimetableFailure.Code.OVERLAP, f);
        assertEquals("b", f.getConflictingCourseId());
    }

    @Test
    public void multiSlotCandidate_secondSlotOverlaps() {
        List<Course> existing = Collections.singletonList(course("a", slot(TUE, 600, 690)));
        Course candidate = course("b", slot(MON, 600, 690), slot(TUE, 680, 740));
        assertCode(TimetableFailure.Code.OVERLAP, CourseValidator.validate(candidate, existing));
    }

    @Test
    public void dayBoundaries_0And1440() {
        List<Course> existing = Collections.singletonList(course("a", slot(MON, 0, 60)));
        assertNull(CourseValidator.validate(course("b", slot(MON, 1380, 1440)), existing));
        assertCode(TimetableFailure.Code.OVERLAP,
                CourseValidator.validate(course("c", slot(MON, 59, 120)), existing));
    }

    @Test
    public void emptyOrNullExisting_noOverlap() {
        assertNull(CourseValidator.validate(course("a", slot(MON, 600, 660)), null));
        assertNull(CourseValidator.validate(course("a", slot(MON, 600, 660)), Collections.emptyList()));
    }

    @Test
    public void invalidFields_reportedBeforeOverlap() {
        List<Course> existing = Collections.singletonList(course("a", slot(MON, 600, 690)));
        Course bad = course("b", slot(MON, 600, 690));
        bad.setName("");
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validate(bad, existing));
    }

    // ── 일괄 ─────────────────────────────────────────

    @Test
    public void validateAll_ok() {
        assertNull(CourseValidator.validateAll(Arrays.asList(
                course("a", slot(MON, 600, 660)), course("b", slot(MON, 660, 720)))));
        assertNull(CourseValidator.validateAll(Collections.emptyList()));
    }

    @Test
    public void validateAll_nullList_invalid() {
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateAll(null));
    }

    @Test
    public void validateAll_duplicateId_invalid() {
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateAll(Arrays.asList(
                course("a", slot(MON, 600, 660)), course("a", slot(TUE, 600, 660)))));
    }

    @Test
    public void validateAll_overlapBetweenCourses() {
        TimetableFailure f = CourseValidator.validateAll(Arrays.asList(
                course("a", slot(MON, 600, 690)), course("b", slot(TUE, 600, 690)), course("c", slot(MON, 680, 720))));
        assertCode(TimetableFailure.Code.OVERLAP, f);
        assertEquals("a", f.getConflictingCourseId());
    }

    // ── sanitize ─────────────────────────────────────

    @Test
    public void sanitize_trimsAndCopies() {
        TimeSlot original = slot(MON, 600, 660);
        Course c = new Course("c", "  자료구조 ", "  ", " 공학관 ", "", new ArrayList<>(Collections.singletonList(original)));
        Course s = CourseValidator.sanitize(c);
        assertEquals("c", s.getCourseId());
        assertEquals("자료구조", s.getName());
        assertNull(s.getProfessor());
        assertEquals("공학관", s.getClassroom());
        assertNull(s.getColorCode());
        assertEquals(original, s.getTimeSlots().get(0));
        original.setEndMinute(700);
        assertEquals(660, s.getTimeSlots().get(0).getEndMinute());
        assertEquals("  자료구조 ", c.getName());
        assertNull(CourseValidator.sanitize(null));
    }

    @Test
    public void sanitize_keepsNullSlotForValidation() {
        Course c = new Course("c", "n", null, null, null, new ArrayList<>(Collections.singletonList((TimeSlot) null)));
        Course s = CourseValidator.sanitize(c);
        assertSame(null, s.getTimeSlots().get(0));
        assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(s));
    }

    // ── PR ① 리뷰 보완 (B) ──────────────────────────

    @Test
    public void courseId_reservedAndDotIdsRejected() {
        for (String id : new String[]{"__name__", "____", "__x__", ".", "..", "a/b"}) {
            Course c = course("x", slot(MON, 600, 660));
            c.setCourseId(id);
            assertCode(TimetableFailure.Code.INVALID_COURSE, CourseValidator.validateFields(c));
        }
        for (String id : new String[]{"_x_", "__x", "x__", "a.b", "...", "__"}) {
            assertEquals(id, true, CourseValidator.isValidDocumentId(id));
        }
    }

    @Test
    public void multiSlotExisting_secondSlotOverlaps() {
        // 기존 수업의 두 번째 칸(화)과 겹친다.
        Course existing = course("e", slot(MON, 600, 690), slot(TUE, 780, 870));
        Course candidate = course("c", slot(TUE, 840, 900));
        TimetableFailure f = CourseValidator.validate(candidate, Collections.singletonList(existing));
        assertCode(TimetableFailure.Code.OVERLAP, f);
        assertEquals("e", f.getConflictingCourseId());
    }

    @Test
    public void multiSlot_noOverlapWhenTouching() {
        Course existing = course("e", slot(MON, 600, 690), slot(TUE, 780, 870));
        Course candidate = course("c", slot(MON, 690, 720), slot(TUE, 870, 900));
        assertNull(CourseValidator.validate(candidate, Collections.singletonList(existing)));
    }

    @Test
    public void existingWithNullCourses_skipped() {
        List<Course> existing = new ArrayList<>(Arrays.asList(null, course("e", slot(MON, 600, 690)), null));
        assertNull(CourseValidator.validate(course("c", slot(TUE, 600, 690)), existing));
        TimetableFailure f = CourseValidator.validate(course("c", slot(MON, 660, 720)), existing);
        assertCode(TimetableFailure.Code.OVERLAP, f);
        assertEquals("e", f.getConflictingCourseId());
    }

    @Test
    public void existingWithNullSlotOrEmptySlots_skipped() {
        Course nullList = new Course("n1", "n", null, null, null, null); // 시간대 없음(빈 목록)
        Course nullSlot = new Course("n2", "n", null, null, null,
                new ArrayList<>(Arrays.asList(null, slot(TUE, 600, 690))));
        List<Course> existing = Arrays.asList(nullList, nullSlot);
        assertNull(CourseValidator.validate(course("c", slot(MON, 600, 690)), existing));
        assertCode(TimetableFailure.Code.OVERLAP,
                CourseValidator.validate(course("c", slot(TUE, 660, 720)), existing));
    }

    private static String repeat(char ch, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ch);
        return sb.toString();
    }
}
