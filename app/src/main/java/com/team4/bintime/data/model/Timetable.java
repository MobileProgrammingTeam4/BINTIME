package com.team4.bintime.data.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 시간표 도메인 모델 — 사용자 1인의 한 학기 시간표.
 * <p>
 * AGENTS.md 계약:
 * <ul>
 *   <li>Firestore: {@code timetables/{uid}} 에 매핑</li>
 *   <li>revision: 수정마다 증가, {@code availability.sourceRevision}과 비교</li>
 * </ul>
 *
 * @see Course
 * @see TimeSlot
 */
public class Timetable implements Parcelable {

    private String timetableId;
    private String userId;
    private String semester;
    private int revision;
    private List<Course> courses;
    private List<TimeSlot> freeSlots;

    public Timetable() {
        this.courses = new ArrayList<>();
        this.freeSlots = new ArrayList<>();
        this.revision = 1;
    }

    public Timetable(String timetableId, String userId, String semester) {
        this.timetableId = timetableId;
        this.userId = userId;
        this.semester = semester;
        this.revision = 1;
        this.courses = new ArrayList<>();
        this.freeSlots = new ArrayList<>();
    }

    // ── Getters / Setters ──────────────────────────────

    public String getTimetableId() {
        return timetableId;
    }

    public void setTimetableId(String timetableId) {
        this.timetableId = timetableId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSemester() {
        return semester;
    }

    public void setSemester(String semester) {
        this.semester = semester;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    /** revision 증가 (시간표 수정 시 호출). */
    public void incrementRevision() {
        this.revision++;
    }

    public List<Course> getCourses() {
        return courses;
    }

    public void setCourses(List<Course> courses) {
        this.courses = courses != null ? courses : new ArrayList<>();
    }

    public List<TimeSlot> getFreeSlots() {
        return freeSlots;
    }

    public void setFreeSlots(List<TimeSlot> freeSlots) {
        this.freeSlots = freeSlots != null ? freeSlots : new ArrayList<>();
    }

    // ── 편의 메서드 ─────────────────────────────────────

    /**
     * courseId로 과목을 찾습니다. 없으면 null.
     */
    public Course findCourseById(String courseId) {
        if (courseId == null || courses == null) return null;
        for (Course c : courses) {
            if (courseId.equals(c.getCourseId())) return c;
        }
        return null;
    }

    /**
     * 과목을 추가합니다. 동일 courseId가 있으면 교체합니다.
     */
    public void addOrUpdateCourse(Course course) {
        if (course == null) return;
        for (int i = 0; i < courses.size(); i++) {
            if (course.getCourseId().equals(courses.get(i).getCourseId())) {
                courses.set(i, course);
                incrementRevision();
                return;
            }
        }
        courses.add(course);
        incrementRevision();
    }

    /**
     * courseId로 과목을 삭제합니다.
     *
     * @return 삭제 성공 여부
     */
    public boolean removeCourse(String courseId) {
        if (courseId == null) return false;
        for (int i = 0; i < courses.size(); i++) {
            if (courseId.equals(courses.get(i).getCourseId())) {
                courses.remove(i);
                incrementRevision();
                return true;
            }
        }
        return false;
    }

    /**
     * 전체 수업 수.
     */
    public int getCourseCount() {
        return courses != null ? courses.size() : 0;
    }

    /**
     * 특정 요일(dayOfWeek)의 모든 수업 TimeSlot을 반환합니다.
     */
    public List<TimeSlot> getCourseSlotsForDay(int dayOfWeek) {
        List<TimeSlot> result = new ArrayList<>();
        if (courses == null) return result;
        for (Course c : courses) {
            if (c.getTimeSlots() == null) continue;
            for (TimeSlot slot : c.getTimeSlots()) {
                if (slot.getDayOfWeek() == dayOfWeek) {
                    result.add(slot);
                }
            }
        }
        return result;
    }

    /**
     * 특정 요일(dayOfWeek)의 설정된 공강 슬롯을 반환합니다.
     */
    public List<TimeSlot> getFreeSlotsForDay(int dayOfWeek) {
        List<TimeSlot> result = new ArrayList<>();
        if (freeSlots == null) return result;
        for (TimeSlot slot : freeSlots) {
            if (slot.getDayOfWeek() == dayOfWeek) {
                result.add(slot);
            }
        }
        return result;
    }

    // ── Parcelable ──────────────────────────────────────

    protected Timetable(Parcel in) {
        timetableId = in.readString();
        userId = in.readString();
        semester = in.readString();
        revision = in.readInt();
        courses = new ArrayList<>();
        in.readTypedList(courses, Course.CREATOR);
        freeSlots = new ArrayList<>();
        in.readTypedList(freeSlots, TimeSlot.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(timetableId);
        dest.writeString(userId);
        dest.writeString(semester);
        dest.writeInt(revision);
        dest.writeTypedList(courses);
        dest.writeTypedList(freeSlots);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Timetable> CREATOR = new Creator<Timetable>() {
        @Override
        public Timetable createFromParcel(Parcel in) {
            return new Timetable(in);
        }

        @Override
        public Timetable[] newArray(int size) {
            return new Timetable[size];
        }
    };

    // ── equals / hashCode ───────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Timetable timetable = (Timetable) o;
        return Objects.equals(timetableId, timetable.timetableId) &&
                Objects.equals(userId, timetable.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timetableId, userId);
    }
}
