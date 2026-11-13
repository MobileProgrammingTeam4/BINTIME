package com.team4.bintime.data.repository;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [BT-015] 수업 저장 전 클라이언트 검증. 규칙(firestore.rules 초안)과 같은 제한을 쓴다.
 * <p>
 * 같은 요일 겹침은 규칙으로 검사할 수 없으므로 여기서 검사하고, Firestore 구현은 트랜잭션에서
 * 부모 문서 revision을 비교해 동시 수정을 막는다(PR ②). 겹침 판정은 {@link TimeSlot#overlaps}만 쓴다.
 * 반열린 구간이라 끝=시작으로 맞닿는 수업은 허용한다.
 */
public final class CourseValidator {

    public static final int MAX_SLOTS_PER_COURSE = 7;
    public static final int MAX_NAME_LENGTH = 30;
    public static final int MAX_TEXT_LENGTH = 30;
    public static final int MAX_COURSE_ID_LENGTH = 128;
    public static final int MINUTES_PER_DAY = 1440;

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    /** Firestore 예약 문서 ID ("__name__" 등). */
    private static final Pattern RESERVED_ID = Pattern.compile("^__.*__$", Pattern.DOTALL);

    private CourseValidator() {
    }

    /**
     * 텍스트 앞뒤 공백을 지우고 빈 선택 필드를 null로 바꾼 사본. 시간대도 복사한다.
     * 저장소는 이 사본을 검증·저장한다.
     */
    public static Course sanitize(Course course) {
        if (course == null) return null;
        List<TimeSlot> slots = new ArrayList<>();
        if (course.getTimeSlots() != null) {
            for (TimeSlot s : course.getTimeSlots()) {
                slots.add(s == null ? null : new TimeSlot(s.getDayOfWeek(), s.getStartMinute(), s.getEndMinute()));
            }
        }
        return new Course(course.getCourseId(),
                course.getName() == null ? null : course.getName().trim(),
                blankToNull(course.getProfessor()),
                blankToNull(course.getClassroom()),
                blankToNull(course.getColorCode()),
                slots);
    }

    /**
     * 필드 형식만 검사한다. 통과하면 null.
     * <p>
     * <b>{@link #sanitize}를 거친 입력을 전제한다.</b> 이름만 trim 후 길이를 보고, 교수·강의실·색상은
     * 앞뒤 공백이 이미 지워지고 빈 값이 null로 바뀌었다고 보고 그대로 검사한다. 저장소는 항상
     * sanitize한 사본을 넘긴다.
     */
    public static TimetableFailure validateFields(Course course) {
        if (course == null) return invalid("course is null");
        if (!isValidDocumentId(course.getCourseId())) return invalid("invalid courseId");
        String name = course.getName();
        if (name == null || name.trim().isEmpty() || name.trim().length() > MAX_NAME_LENGTH) {
            return invalid("name must be 1.." + MAX_NAME_LENGTH + " chars");
        }
        if (tooLong(course.getProfessor())) return invalid("professor too long");
        if (tooLong(course.getClassroom())) return invalid("classroom too long");
        if (course.getColorCode() != null && !COLOR.matcher(course.getColorCode()).matches()) {
            return invalid("colorCode must be #RRGGBB");
        }
        List<TimeSlot> slots = course.getTimeSlots();
        if (slots == null || slots.isEmpty() || slots.size() > MAX_SLOTS_PER_COURSE) {
            return invalid("timeSlots must be 1.." + MAX_SLOTS_PER_COURSE);
        }
        for (TimeSlot s : slots) {
            if (!isValidSlot(s)) return invalid("invalid time slot " + s);
        }
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                if (slots.get(i).overlaps(slots.get(j))) return invalid("time slots of the course overlap");
            }
        }
        return null;
    }

    /**
     * candidate와 같은 요일에 시간이 겹치는 기존 수업(같은 courseId는 제외). 없으면 null.
     */
    public static Course findOverlap(Course candidate, List<Course> existing) {
        if (candidate == null || candidate.getTimeSlots() == null || existing == null) return null;
        for (Course other : existing) {
            if (other == null || other.getTimeSlots() == null) continue;
            if (candidate.getCourseId() != null && candidate.getCourseId().equals(other.getCourseId())) continue;
            for (TimeSlot mine : candidate.getTimeSlots()) {
                for (TimeSlot theirs : other.getTimeSlots()) {
                    if (mine != null && mine.overlaps(theirs)) return other;
                }
            }
        }
        return null;
    }

    /** 필드 검사 후 기존 수업과의 겹침 검사. 통과하면 null. */
    public static TimetableFailure validate(Course candidate, List<Course> existing) {
        TimetableFailure failure = validateFields(candidate);
        if (failure != null) return failure;
        Course conflict = findOverlap(candidate, existing);
        return conflict == null ? null : TimetableFailure.overlap(conflict.getCourseId());
    }

    /** 일괄 교체용: 각 수업 필드, courseId 중복, 수업끼리의 겹침을 검사한다. 통과하면 null. */
    public static TimetableFailure validateAll(List<Course> courses) {
        if (courses == null) return invalid("courses is null");
        Set<String> ids = new HashSet<>();
        List<Course> checked = new ArrayList<>();
        for (Course c : courses) {
            TimetableFailure failure = validateFields(c);
            if (failure != null) return failure;
            if (!ids.add(c.getCourseId())) return invalid("duplicate courseId " + c.getCourseId());
            Course conflict = findOverlap(c, checked);
            if (conflict != null) return TimetableFailure.overlap(conflict.getCourseId());
            checked.add(c);
        }
        return null;
    }

    /**
     * Firestore 문서 ID로 쓸 수 있는지. 비어 있지 않고 '/' 없음, 128자 이하, "."·".." 아님,
     * "__.*__" 형태(Firestore 예약 ID) 아님. 규칙의 validDocId와 같은 조건이다.
     */
    public static boolean isValidDocumentId(String id) {
        return id != null && !id.trim().isEmpty() && !id.contains("/") && id.length() <= MAX_COURSE_ID_LENGTH
                && !id.equals(".") && !id.equals("..") && !RESERVED_ID.matcher(id).matches();
    }

    /** 요일 1..7, 0 <= start < end <= 1440. 30분 단위가 아니어도 된다. */
    public static boolean isValidSlot(TimeSlot s) {
        return s != null
                && s.getDayOfWeek() >= 1 && s.getDayOfWeek() <= 7
                && s.getStartMinute() >= 0
                && s.getStartMinute() < s.getEndMinute()
                && s.getEndMinute() <= MINUTES_PER_DAY;
    }

    private static boolean tooLong(String text) {
        return text != null && text.length() > MAX_TEXT_LENGTH;
    }

    private static String blankToNull(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static TimetableFailure invalid(String detail) {
        return TimetableFailure.of(TimetableFailure.Code.INVALID_COURSE, detail);
    }
}
