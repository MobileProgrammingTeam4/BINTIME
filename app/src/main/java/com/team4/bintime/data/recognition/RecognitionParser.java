package com.team4.bintime.data.recognition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [BT-026] analyzeTimetable Callable 응답(Map) → {@link RecognitionResult}. 서버가 zod 로 이미 검사하지만 앱은
 * 응답을 믿지 않고 같은 범위로 다시 본다.
 * <ul>
 *   <li>응답 자체가 객체가 아니거나 {@code courses}가 목록이 아니면 null(→ AI_INVALID_OUTPUT).</li>
 *   <li>수업 하나가 틀리면(필드 누락, 범위 밖, sourceImageIndex ≥ 이미지 수) 그 수업만 빼고 {@code droppedCount}를 센다.</li>
 *   <li>문자열은 앞뒤 공백을 지운다. 이름 1~60자, 건물·강의실 1~40자 또는 null(서버 계약). 앱 제한(30자)은 여기서
 *       자르지 않고 검수 단계({@link RecognitionReview})에서 "수정 필요"로 표시한다.</li>
 *   <li>정수 필드는 Firebase 가 Integer·Long·Double 로 줄 수 있어 정수 값인지 확인한다.</li>
 * </ul>
 * 원문·수업명은 로그에 남기지 않는다. Android 의존성이 없어 JVM 단위 테스트로 검증한다.
 */
public final class RecognitionParser {

    static final int MAX_COURSES = 60;
    static final int MAX_WARNINGS = 20;
    static final int MAX_NAME = 60;
    static final int MAX_PLACE = 40;
    static final int MAX_WARNING = 200;

    private RecognitionParser() {
    }

    /** @return 결과, 응답 형식 자체가 틀리면 null */
    public static RecognitionResult parse(Object data, int imageCount) {
        if (!(data instanceof Map)) return null;
        Map<?, ?> root = (Map<?, ?>) data;
        Object rawCourses = root.get("courses");
        if (!(rawCourses instanceof List)) return null;
        List<RecognizedCourse> courses = new ArrayList<>();
        int dropped = 0;
        for (Object raw : (List<?>) rawCourses) {
            RecognizedCourse course = courses.size() < MAX_COURSES ? course(raw, imageCount) : null;
            if (course == null) dropped++;
            else courses.add(course);
        }
        List<String> warnings = new ArrayList<>();
        Object rawWarnings = root.get("warnings");
        if (rawWarnings instanceof List) {
            for (Object w : (List<?>) rawWarnings) {
                String text = text(w, 1, MAX_WARNING);
                if (text != null && warnings.size() < MAX_WARNINGS) warnings.add(text);
            }
        }
        return new RecognitionResult(courses, warnings, dropped, imageCount);
    }

    static RecognizedCourse course(Object raw, int imageCount) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) raw;
        String name = text(m.get("name"), 1, MAX_NAME);
        Integer day = integer(m.get("dayOfWeek"));
        Integer start = integer(m.get("startMinute"));
        Integer end = integer(m.get("endMinute"));
        Double confidence = number(m.get("confidence"));
        Integer source = integer(m.get("sourceImageIndex"));
        if (name == null || day == null || start == null || end == null || confidence == null || source == null) {
            return null;
        }
        if (day < 1 || day > 7 || start < 0 || start > 1439 || end < 1 || end > 1440 || start >= end) return null;
        if (confidence < 0 || confidence > 1) return null;
        if (source < 0 || source >= imageCount) return null;
        // building·room 은 키가 반드시 있어야 한다(서버 스키마: nullable 이지만 생략 불가).
        if (!m.containsKey("building") || !m.containsKey("room")) return null;
        Object rawBuilding = m.get("building");
        Object rawRoom = m.get("room");
        String building = rawBuilding == null ? null : text(rawBuilding, 1, MAX_PLACE);
        String room = rawRoom == null ? null : text(rawRoom, 1, MAX_PLACE);
        if ((rawBuilding != null && building == null) || (rawRoom != null && room == null)) return null;
        return new RecognizedCourse(name, day, start, end, building, room, confidence, source);
    }

    private static String text(Object value, int min, int max) {
        if (!(value instanceof String)) return null;
        String trimmed = ((String) value).trim();
        return trimmed.length() < min || trimmed.length() > max ? null : trimmed;
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number)) return null;
        double d = ((Number) value).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)
                || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            return null;
        }
        return (int) d;
    }

    private static Double number(Object value) {
        if (!(value instanceof Number)) return null;
        double d = ((Number) value).doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }
}
