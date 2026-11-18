package com.team4.bintime.timetable;

import java.util.Objects;

/**
 * [BT-026] 수업 입력·수정 화면 규칙(순수 Java, 화면 없이 테스트한다).
 * <ul>
 *   <li>강의실은 기본 필수다(직접 입력, 수업 추가, 원래 강의실이 있던 수업 수정).</li>
 *   <li><b>원래 강의실이 없던 수업</b>(인식 결과 building·room 이 null, 또는 기존 null 수업)을 수정할 때만
 *       비워 둘 수 있다. 저장소·Rules 는 강의실을 선택 항목으로 본다(CourseValidator, optionalText).</li>
 *   <li>빈 강의실("", 공백)과 null 은 같은 값이다. 비교·저장 모두 null 로 맞춘다.</li>
 * </ul>
 */
final class CourseFormRules {

    private CourseFormRules() {
    }

    /** 앞뒤 공백을 지우고, 비었으면 null. */
    static String normalizeClassroom(String classroom) {
        if (classroom == null) return null;
        String trimmed = classroom.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 수정 화면에서 강의실을 비워 둘 수 있는가. 추가(original == null)·직접 입력은 항상 필수. */
    static boolean classroomOptional(Course original) {
        return original != null && normalizeClassroom(original.getClassroom()) == null;
    }

    /** 강의실이 필요한데 비어 있으면 true(저장을 막는다). */
    static boolean missingClassroom(String classroom, boolean optional) {
        return !optional && normalizeClassroom(classroom) == null;
    }

    /** 화면에 보이는 값이 원본과 같은가. 강의실 ""·공백·null 은 같다고 본다. */
    static boolean sameAsOriginal(Course original, Course shown) {
        if (original == null || shown == null) return original == shown;
        return Objects.equals(trim(original.getName()), trim(shown.getName()))
                && Objects.equals(normalizeClassroom(original.getClassroom()),
                normalizeClassroom(shown.getClassroom()))
                && Objects.equals(original.getDay(), shown.getDay())
                && Objects.equals(original.getStartTime(), shown.getStartTime())
                && Objects.equals(original.getEndTime(), shown.getEndTime());
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }
}
