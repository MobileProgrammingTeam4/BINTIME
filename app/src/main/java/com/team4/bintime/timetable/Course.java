package com.team4.bintime.timetable;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 시간표 수업 모델 (직접 입력, 이미지 인식, 수정, 주간 그리드 전달용)
 * <p>
 * [BT-015] 저장소({@code data.model.Course})와 연결하기 위해 courseId·professor·colorCode를 함께 들고 다닌다.
 * 화면에는 보이지 않으며, 수정 저장 때 같은 문서를 가리키고 화면에 없는 값을 지우지 않게 한다.
 * equals/hashCode는 화면 입력값(이름·강의실·요일·시간)만 비교한다(수정 취소 확인용).
 * 여러 시간대를 가진 저장소 수업은 같은 courseId를 가진 여러 행으로 펼쳐진다({@link TimetableDraftMapper}).
 */
public class Course implements Serializable {

    private String courseId;
    private String professor;
    private String colorCode;
    /**
     * [BT-026] 이미지 인식으로 만든 행이면 그 신뢰도(0~1), 아니면 NaN. 합친 원본 항목 수(중복 병합 표시용).
     * 화면 입력값이 아니라 equals 에 넣지 않고, 사용자가 수정하면 새 행이 되어 사라진다(검수했다는 뜻).
     */
    private double recognitionConfidence = Double.NaN;
    private int recognitionMergedCount;

    private String name;
    private String classroom;
    private String day;        // 예: "월", "월요일"
    private String startTime;  // 24시간제 "HH:mm" (예: "10:00")
    private String endTime;    // 24시간제 "HH:mm" (예: "11:30")

    public Course() {
        this.courseId = UUID.randomUUID().toString();
    }

    public Course(String name, String classroom, String day, String startTime, String endTime) {
        this.courseId = UUID.randomUUID().toString();
        this.name = name;
        this.classroom = classroom;
        this.day = day;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /** 저장소 문서 ID. 이전 버전에서 복원된 객체는 null일 수 있다. */
    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }

    public String getProfessor() {
        return professor;
    }

    public void setProfessor(String professor) {
        this.professor = professor;
    }

    public String getColorCode() {
        return colorCode;
    }

    public void setColorCode(String colorCode) {
        this.colorCode = colorCode;
    }

    /** [BT-026] 인식 결과에서 온 행으로 표시한다. */
    void markRecognized(double confidence, int mergedCount) {
        recognitionConfidence = confidence;
        recognitionMergedCount = mergedCount;
    }

    /** 인식 신뢰도. 인식 행이 아니거나 사용자가 수정했으면 NaN. */
    double getRecognitionConfidence() {
        return recognitionConfidence;
    }

    int getRecognitionMergedCount() {
        return recognitionMergedCount;
    }

    /** 수정한 값에 원래 수업의 courseId·professor·colorCode를 이어 붙인다(화면에 없는 값 보존). */
    void keepIdentityOf(Course original) {
        if (original == null) return;
        courseId = original.courseId;
        professor = original.professor;
        colorCode = original.colorCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getClassroom() {
        return classroom;
    }

    public void setClassroom(String classroom) {
        this.classroom = classroom;
    }

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    /**
     * 요일 약칭 반환 ("월", "화", "수", "목", "금", "토", "일")
     */
    public String getShortDay() {
        if (day == null || day.isEmpty()) return "월";
        return day.substring(0, 1);
    }

    /**
     * 요일의 0-based 인덱스 반환 (월: 0, 화: 1, 수: 2, 목: 3, 금: 4)
     * 주말 또는 알 수 없는 경우 -1
     */
    public int getDayIndex() {
        String s = getShortDay();
        switch (s) {
            case "월": return 0;
            case "화": return 1;
            case "수": return 2;
            case "목": return 3;
            case "금": return 4;
            default: return -1;
        }
    }

    /**
     * "10:00" 형태의 문자열을 분(minute) 단위 정수로 변환 (예: 600)
     */
    public static int parseMinute(String timeStr) {
        if (timeStr == null || !timeStr.contains(":")) return 0;
        try {
            String[] parts = timeStr.trim().split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            return h * 60 + m;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getStartMinute() {
        return parseMinute(startTime);
    }

    public int getEndMinute() {
        return parseMinute(endTime);
    }

    /**
     * 30분 단위 슬롯 인덱스 계산 (baseHour = 9시 기준)
     * 예: 09:00 -> 슬롯 0, 09:30 -> 슬롯 1, 10:00 -> 슬롯 2
     */
    public int getStartSlotIndex(int baseHour) {
        int minutes = getStartMinute();
        int baseMinutes = baseHour * 60;
        if (minutes < baseMinutes) return 0;
        return (minutes - baseMinutes) / 30;
    }

    /**
     * 종료 슬롯 인덱스 (exclusive가 아닌 종료 시점 기준 슬롯 수)
     */
    public int getEndSlotIndex(int baseHour) {
        int minutes = getEndMinute();
        int baseMinutes = baseHour * 60;
        if (minutes <= baseMinutes) return 0;
        return (minutes - baseMinutes + 29) / 30; // 올림 처리하여 해당 30분 슬롯 포함
    }

    /**
     * Figma 및 목록용 요약 텍스트 생성: "월 10:00 ~ 11:30 · 공학관 302호"
     */
    public String getFormattedInfo() {
        return String.format(Locale.KOREA, "%s %s ~ %s · %s",
                getShortDay(),
                startTime != null ? startTime : "10:00",
                endTime != null ? endTime : "11:30",
                classroom != null ? classroom : "강의실 미정");
    }

    /**
     * 오전/오후 및 12시간제 숫자를 24시간제 "HH:mm" 포맷으로 변환
     * - 오전 12시 -> "00:mm"
     * - 오전 01~11시 -> "01:mm" ~ "11:mm"
     * - 오후 12시 -> "12:mm"
     * - 오후 01~11시 -> "13:mm" ~ "23:mm"
     */
    public static String format24Hour(boolean isPm, int hour12, int minute) {
        int hour24;
        if (hour12 == 12) {
            hour24 = isPm ? 12 : 0;
        } else {
            hour24 = isPm ? hour12 + 12 : hour12;
        }
        return String.format(Locale.KOREA, "%02d:%02d", hour24, minute);
    }

    /**
     * "월 10:00 ~ 11:30 · 공학관 302호" 포맷 문자열 파싱
     */
    public static Course parse(String title, String info) {
        Course course = new Course();
        course.setName(title != null ? title : "수업");
        course.setClassroom("강의실");
        course.setDay("월");
        course.setStartTime("10:00");
        course.setEndTime("11:30");

        if (info == null || info.isEmpty()) return course;

        try {
            String[] dotParts = info.split("·");
            if (dotParts.length > 1) {
                course.setClassroom(dotParts[1].trim());
            }

            String left = dotParts[0].trim();
            String[] spaceParts = left.split(" ");
            if (spaceParts.length > 0) {
                String d = spaceParts[0].trim();
                course.setDay(d.length() > 0 ? d.substring(0, 1) : "월");
            }

            if (left.contains("~")) {
                String[] timeParts = left.split("~");
                String startPart = timeParts[0].replaceAll("[^0-9:]", "").trim();
                String endPart = timeParts[1].replaceAll("[^0-9:]", "").trim();

                if (!startPart.isEmpty()) course.setStartTime(startPart);
                if (!endPart.isEmpty()) course.setEndTime(endPart);
            }
        } catch (Exception ignored) {
        }

        return course;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Course course = (Course) o;
        return Objects.equals(name, course.name) &&
                Objects.equals(classroom, course.classroom) &&
                Objects.equals(day, course.day) &&
                Objects.equals(startTime, course.startTime) &&
                Objects.equals(endTime, course.endTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, classroom, day, startTime, endTime);
    }
}
