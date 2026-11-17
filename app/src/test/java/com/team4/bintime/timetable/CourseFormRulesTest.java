package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.recognition.RecognitionResult;
import com.team4.bintime.data.recognition.RecognizedCourse;
import com.team4.bintime.data.repository.CourseValidator;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * [BT-026] 수정 화면 강의실 규칙: 원래 강의실이 없던 수업만 비워 둘 수 있고, ""·공백·null 은 같은 값이며,
 * 바꾼 게 없으면 수정 버튼이 꺼진다(CourseModifyActivity.updateSaveButton 이 sameAsOriginal 을 쓴다).
 */
public class CourseFormRulesTest {

    private static Course recognizedWithoutClassroom() {
        TimetableDraft draft = RecognitionDrafts.toDraft(new RecognitionResult(Collections.singletonList(
                new RecognizedCourse("확률과 통계", 4, 540, 630, null, null, 0.52, 0)),
                Collections.emptyList(), 0, 1));
        return draft.courses.get(0);
    }

    /** 수정 화면이 보여 주는 값(CourseFields.value): 강의실 칸 문자열을 정리해 null 로 만든다. */
    private static Course shown(Course base, String classroomText, String day, String start, String end) {
        return new Course(base.getName(), CourseFormRules.normalizeClassroom(classroomText), day, start, end);
    }

    @Test
    public void nullClassroomCourse_canBeSavedWithEmptyClassroom() {
        Course original = recognizedWithoutClassroom();
        assertNull(original.getClassroom());
        boolean optional = CourseFormRules.classroomOptional(original);
        assertTrue(optional);
        assertFalse(CourseFormRules.missingClassroom("", optional));
        assertFalse(CourseFormRules.missingClassroom("   ", optional));
        assertFalse(CourseFormRules.missingClassroom(null, optional));

        // 요일·시간만 바꾼 결과를 draft 에 넣고 저장 모델로 바꿔도 강의실 null 로 검증을 통과한다.
        Course edited = shown(original, "", "월", "13:00", "14:30");
        edited.keepIdentityOf(original);
        assertNull(edited.getClassroom());
        TimetableDraft draft = new TimetableDraft(Collections.singletonList(edited));
        assertNull(CourseValidator.validateAll(TimetableDraftMapper.toCourses(draft)));
        assertNull(TimetableDraftMapper.toCourses(draft).get(0).getClassroom());
    }

    @Test
    public void existingNullClassroomCourse_isOptionalToo() {
        Course existing = new Course("세미나", null, "금", "12:00", "12:30");
        assertTrue(CourseFormRules.classroomOptional(existing));
        assertTrue(CourseFormRules.classroomOptional(new Course("세미나", "  ", "금", "12:00", "12:30")));
    }

    @Test
    public void courseWithClassroom_blankingIsBlocked() {
        Course original = new Course("컴퓨터 구조론", "공학관 302호", "월", "10:00", "11:30");
        boolean optional = CourseFormRules.classroomOptional(original);
        assertFalse(optional);
        assertTrue(CourseFormRules.missingClassroom("", optional));
        assertTrue(CourseFormRules.missingClassroom("  ", optional));
        assertFalse(CourseFormRules.missingClassroom("공학관 302호", optional));
    }

    @Test
    public void newDirectInputOrAdd_classroomRequired() {
        // 직접 입력·수업 추가는 allowEmptyClassroom 을 부르지 않는다(optional = false).
        assertFalse(CourseFormRules.classroomOptional(null));
        assertTrue(CourseFormRules.missingClassroom("", false));
        assertTrue(CourseFormRules.missingClassroom(null, false));
        assertFalse(CourseFormRules.missingClassroom("R101", false));
    }

    @Test
    public void emptyAndNullClassroom_areTheSame() {
        assertNull(CourseFormRules.normalizeClassroom(null));
        assertNull(CourseFormRules.normalizeClassroom(""));
        assertNull(CourseFormRules.normalizeClassroom(" \t "));
        assertEquals("공학관 302호", CourseFormRules.normalizeClassroom(" 공학관 302호 "));

        Course withNull = new Course("수학", null, "수", "09:00", "10:00");
        Course withEmpty = new Course("수학", "", "수", "09:00", "10:00");
        assertTrue(CourseFormRules.sameAsOriginal(withNull, withEmpty));
        assertTrue(CourseFormRules.sameAsOriginal(withEmpty, withNull));
    }

    @Test
    public void unchangedNullClassroomCourse_saveButtonStaysOff() {
        Course original = recognizedWithoutClassroom();
        // 수정 화면은 null 강의실을 빈 칸으로 보여 준다. 그대로면 "원본과 같음" → 수정 버튼 비활성.
        Course untouched = shown(original, "", original.getDay(), original.getStartTime(), original.getEndTime());
        assertTrue(CourseFormRules.sameAsOriginal(original, untouched));
        // 시간을 바꾸면 달라져 버튼이 켜진다.
        assertFalse(CourseFormRules.sameAsOriginal(original,
                shown(original, "", original.getDay(), "13:00", original.getEndTime())));
        // 강의실을 새로 채워도 달라진다.
        assertFalse(CourseFormRules.sameAsOriginal(original,
                shown(original, "본관", original.getDay(), original.getStartTime(), original.getEndTime())));
    }

    @Test
    public void unchangedCourseWithClassroom_saveButtonStaysOff() {
        Course original = new Course("컴퓨터 구조론", "공학관 302호", "월", "10:00", "11:30");
        assertTrue(CourseFormRules.sameAsOriginal(original,
                shown(original, " 공학관 302호 ", "월", "10:00", "11:30")));
        assertFalse(CourseFormRules.sameAsOriginal(original, shown(original, "", "월", "10:00", "11:30")));
    }

    @Test
    public void emptyAddForm_isUnchangedUntilTyping() {
        // 수업 추가 화면의 원본은 CourseFields.empty()(강의실 "")이고 화면 값은 null 로 정리된다.
        Course empty = CourseFields.empty();
        assertTrue(CourseFormRules.sameAsOriginal(empty, shown(empty, "", "월", "10:00", "11:30")));
        assertEquals(Arrays.asList(true, false), Arrays.asList(
                CourseFormRules.sameAsOriginal(null, null), CourseFormRules.sameAsOriginal(empty, null)));
    }
}
