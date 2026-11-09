package com.team4.bintime.data.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 공통 도메인 수업 모델 (Single Source of Truth).
 * <p>
 * 화면 간 수업 데이터는 이 클래스(Parcelable)로만 주고받습니다.
 * BT-012 요구사항:
 * <ul>
 *   <li>고유 ID (courseId) — Firestore 문서 키</li>
 *   <li>교수명 (professor)</li>
 *   <li>색상 코드 (colorCode) — 시간표 그리드 표시용</li>
 *   <li>다중 시간 슬롯 ({@code List<TimeSlot>}) — 하나의 수업이 월/수 등 여러 시간대에 열릴 수 있음</li>
 *   <li>Parcelable — Activity 간 데이터 전달</li>
 * </ul>
 *
 * @see TimeSlot
 */
public class Course implements Parcelable {

    private String courseId;
    private String name;
    private String professor;
    private String classroom;
    private String colorCode;
    private List<TimeSlot> timeSlots;

    /** 기본 색상 팔레트 (그리드에서 수업 구분용) */
    public static final String[] COLOR_PALETTE = {
            "#FFE0E0", "#E0F0FF", "#E0FFE0", "#FFF5CC",
            "#F0E0FF", "#FFE8CC", "#E0FFF0", "#FFE0F0"
    };

    public Course() {
        this.courseId = UUID.randomUUID().toString();
        this.timeSlots = new ArrayList<>();
    }

    public Course(String courseId, String name, String professor, String classroom,
                  String colorCode, List<TimeSlot> timeSlots) {
        this.courseId = courseId != null ? courseId : UUID.randomUUID().toString();
        this.name = name;
        this.professor = professor;
        this.classroom = classroom;
        this.colorCode = colorCode;
        this.timeSlots = timeSlots != null ? timeSlots : new ArrayList<>();
    }

    // ── Getters / Setters ──────────────────────────────

    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProfessor() {
        return professor;
    }

    public void setProfessor(String professor) {
        this.professor = professor;
    }

    public String getClassroom() {
        return classroom;
    }

    public void setClassroom(String classroom) {
        this.classroom = classroom;
    }

    public String getColorCode() {
        return colorCode;
    }

    public void setColorCode(String colorCode) {
        this.colorCode = colorCode;
    }

    public List<TimeSlot> getTimeSlots() {
        return timeSlots;
    }

    public void setTimeSlots(List<TimeSlot> timeSlots) {
        this.timeSlots = timeSlots != null ? timeSlots : new ArrayList<>();
    }

    // ── 편의 메서드 ─────────────────────────────────────

    /**
     * 단일 시간 슬롯 과목을 빠르게 생성하는 팩토리.
     * 요일·시간 파싱에 실패하거나 종료 &lt;= 시작이면 슬롯 없이 반환합니다.
     *
     * @param name       과목명
     * @param classroom  강의실
     * @param dayStr     요일 문자열 ("월", "화요일" 등)
     * @param startTime  시작 시간 "HH:mm"
     * @param endTime    종료 시간 "HH:mm"
     * @return 새 Course 인스턴스
     */
    public static Course createSingle(String name, String classroom,
                                      String dayStr, String startTime, String endTime) {
        Course course = new Course();
        course.setName(name);
        course.setClassroom(classroom);

        int dow = TimeSlot.parseDayOfWeek(dayStr);
        int start = TimeSlot.parseTimeToMinutes(startTime);
        int end = TimeSlot.parseTimeToMinutes(endTime);

        if (dow > 0 && start >= 0 && end > start) {
            course.getTimeSlots().add(new TimeSlot(dow, start, end));
        }

        return course;
    }

    /**
     * 첫 번째 시간 슬롯의 요일 약칭 ("월" 등). 슬롯이 없으면 "?".
     */
    public String getFirstDayLabel() {
        if (timeSlots == null || timeSlots.isEmpty()) return "?";
        return timeSlots.get(0).getDayLabel();
    }

    /**
     * Figma 목록용 요약 텍스트 생성.
     * 예: "월 10:00 ~ 11:30 · 공학관 302호"
     */
    public String getFormattedInfo() {
        if (timeSlots == null || timeSlots.isEmpty()) {
            return String.format(Locale.KOREA, "? 00:00 ~ 00:00 · %s",
                    classroom != null ? classroom : "강의실 미정");
        }
        TimeSlot slot = timeSlots.get(0);
        return String.format(Locale.KOREA, "%s %s ~ %s · %s",
                slot.getDayLabel(),
                TimeSlot.minutesToTimeString(slot.getStartMinute()),
                TimeSlot.minutesToTimeString(slot.getEndMinute()),
                classroom != null ? classroom : "강의실 미정");
    }

    // ── Parcelable ──────────────────────────────────────

    protected Course(Parcel in) {
        courseId = in.readString();
        name = in.readString();
        professor = in.readString();
        classroom = in.readString();
        colorCode = in.readString();
        timeSlots = new ArrayList<>();
        in.readTypedList(timeSlots, TimeSlot.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(courseId);
        dest.writeString(name);
        dest.writeString(professor);
        dest.writeString(classroom);
        dest.writeString(colorCode);
        dest.writeTypedList(timeSlots);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Course> CREATOR = new Creator<Course>() {
        @Override
        public Course createFromParcel(Parcel in) {
            return new Course(in);
        }

        @Override
        public Course[] newArray(int size) {
            return new Course[size];
        }
    };

    // ── equals / hashCode ───────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Course course = (Course) o;
        return Objects.equals(courseId, course.courseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(courseId);
    }

    @Override
    public String toString() {
        return "Course{" + name + ", slots=" + (timeSlots != null ? timeSlots.size() : 0) + "}";
    }
}
