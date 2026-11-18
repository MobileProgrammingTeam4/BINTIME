package com.team4.bintime.data.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** [BT-026] analyzeTimetable 응답 검사: 필드 누락, 범위 밖 값, sourceImageIndex, 숫자 형식, 응답 형식. */
public class RecognitionParserTest {

    private static Map<String, Object> course() {
        Map<String, Object> c = new HashMap<>();
        c.put("name", "  자료구조 ");
        c.put("dayOfWeek", 3);
        c.put("startMinute", 780);
        c.put("endMinute", 870);
        c.put("building", "미래관");
        c.put("room", "407호");
        c.put("confidence", 0.88);
        c.put("sourceImageIndex", 0);
        return c;
    }

    private static Map<String, Object> response(List<?> courses) {
        Map<String, Object> r = new HashMap<>();
        r.put("courses", courses);
        r.put("warnings", Collections.singletonList("안내"));
        return r;
    }

    private static RecognitionResult parseOne(Map<String, Object> c, int images) {
        return RecognitionParser.parse(response(Collections.singletonList(c)), images);
    }

    @Test
    public void validCourse_isParsed_andTextTrimmed() {
        RecognitionResult r = parseOne(course(), 1);
        assertEquals(1, r.courses.size());
        assertEquals(0, r.droppedCount);
        RecognizedCourse c = r.courses.get(0);
        assertEquals("자료구조", c.name);
        assertEquals(3, c.dayOfWeek);
        assertEquals(780, c.startMinute);
        assertEquals(870, c.endMinute);
        assertEquals("미래관", c.building);
        assertEquals("407호", c.room);
        assertEquals(0.88, c.confidence, 1e-9);
        assertEquals(Collections.singletonList("안내"), r.warnings);
    }

    @Test
    public void missingFields_dropOnlyThatCourse() {
        for (String key : new String[]{"name", "dayOfWeek", "startMinute", "endMinute", "confidence",
                "sourceImageIndex", "building", "room"}) {
            Map<String, Object> broken = course();
            broken.remove(key);
            RecognitionResult r = RecognitionParser.parse(response(Arrays.asList(broken, course())), 1);
            assertEquals(key, 1, r.courses.size());
            assertEquals(key, 1, r.droppedCount);
        }
    }

    @Test
    public void nullBuildingAndRoom_areAllowed() {
        Map<String, Object> c = course();
        c.put("building", null);
        c.put("room", null);
        RecognizedCourse parsed = parseOne(c, 1).courses.get(0);
        assertNull(parsed.building);
        assertNull(parsed.room);
    }

    @Test
    public void outOfRangeValues_areDropped() {
        Object[][] bad = {
                {"dayOfWeek", 0}, {"dayOfWeek", 8}, {"startMinute", -1}, {"startMinute", 1440},
                {"endMinute", 0}, {"endMinute", 1441}, {"endMinute", 780}, // start == end
                {"confidence", -0.01}, {"confidence", 1.01}, {"name", "   "}, {"name", repeat('가', 61)},
                {"building", ""}, {"room", repeat('호', 41)}, {"dayOfWeek", 3.5}, {"startMinute", "780"},
                {"confidence", Double.NaN}, {"name", 123},
        };
        for (Object[] b : bad) {
            Map<String, Object> c = course();
            c.put((String) b[0], b[1]);
            RecognitionResult r = parseOne(c, 1);
            assertEquals(b[0] + "=" + b[1], 0, r.courses.size());
            assertEquals(1, r.droppedCount);
        }
    }

    @Test
    public void boundaryValues_areAccepted() {
        Map<String, Object> c = course();
        c.put("startMinute", 0);
        c.put("endMinute", 1440);
        c.put("dayOfWeek", 7);
        c.put("confidence", 0);
        c.put("name", repeat('가', 60)); // 서버 상한 60. 앱 상한 30 은 검수에서 "수정 필요"
        c.put("room", repeat('호', 40));
        assertEquals(1, parseOne(c, 1).courses.size());
    }

    @Test
    public void sourceImageIndex_mustBeWithinImageCount() {
        Map<String, Object> c = course();
        c.put("sourceImageIndex", 1);
        assertEquals(0, parseOne(c, 1).courses.size());
        assertEquals(1, parseOne(c, 2).courses.size());
        c.put("sourceImageIndex", -1);
        assertEquals(0, parseOne(c, 2).courses.size());
    }

    @Test
    public void firebaseNumberTypes_areAccepted() {
        Map<String, Object> c = course();
        c.put("dayOfWeek", 3L);
        c.put("startMinute", 780.0);
        c.put("confidence", 1); // 정수 1 도 0~1 범위
        assertEquals(1, parseOne(c, 1).courses.size());
    }

    @Test
    public void malformedResponse_isNull() {
        assertNull(RecognitionParser.parse(null, 1));
        assertNull(RecognitionParser.parse("text", 1));
        Map<String, Object> noCourses = new HashMap<>();
        noCourses.put("warnings", Collections.emptyList());
        assertNull(RecognitionParser.parse(noCourses, 1));
        Map<String, Object> wrongType = new HashMap<>();
        wrongType.put("courses", "x");
        assertNull(RecognitionParser.parse(wrongType, 1));
    }

    @Test
    public void emptyCourses_andMissingWarnings_areFine() {
        Map<String, Object> r = new HashMap<>();
        r.put("courses", new ArrayList<>());
        RecognitionResult parsed = RecognitionParser.parse(r, 1);
        assertNotNull(parsed);
        assertEquals(0, parsed.courses.size());
        assertEquals(0, parsed.warnings.size());
    }

    @Test
    public void moreThanSixtyCourses_extraAreDropped() {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 61; i++) many.add(course());
        RecognitionResult r = RecognitionParser.parse(response(many), 1);
        assertEquals(60, r.courses.size());
        assertEquals(1, r.droppedCount);
    }

    @Test
    public void toString_hasNoCourseText() {
        RecognitionResult r = parseOne(course(), 1);
        assertEquals(-1, r.courses.get(0).toString().indexOf("자료구조"));
        assertEquals(-1, r.courses.get(0).toString().indexOf("미래관"));
    }

    // ── [P4] Callable 결과(감싼 응답) ─────────────────────────────

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> e = new HashMap<>();
        e.put("task", "analyzeTimetable");
        e.put("data", data);
        e.put("meta", new HashMap<>());
        return e;
    }

    @Test
    public void parseCallable_unwrapsData() {
        RecognitionResult r = RecognitionParser.parseCallable(envelope(response(Collections.singletonList(course()))), 1);
        assertEquals(1, r.courses.size());
        assertEquals("자료구조", r.courses.get(0).name);
        assertEquals(Collections.singletonList("안내"), r.warnings);
    }

    @Test
    public void parseCallable_unwrappedOrMissingData_isNull() {
        assertNull(RecognitionParser.parseCallable(response(Collections.singletonList(course())), 1));
        assertNull(RecognitionParser.parseCallable(new HashMap<>(), 1));
        assertNull(RecognitionParser.parseCallable(envelope("x"), 1));
        assertNull(RecognitionParser.parseCallable(null, 1));
    }

    @Test
    public void parseCallable_allCoursesDropped_isNull_butEmptyResponseIsEmptyResult() {
        Map<String, Object> bad = course();
        bad.put("dayOfWeek", 9);
        assertNull(RecognitionParser.parseCallable(envelope(response(Arrays.asList(bad, bad))), 1));

        Map<String, Object> mixed = course();
        RecognitionResult partly = RecognitionParser.parseCallable(envelope(response(Arrays.asList(bad, mixed))), 1);
        assertEquals(1, partly.courses.size());
        assertEquals(1, partly.droppedCount);

        RecognitionResult empty = RecognitionParser.parseCallable(envelope(response(new ArrayList<>())), 1);
        assertNotNull(empty);
        assertEquals(0, empty.courses.size());
        assertEquals(0, empty.droppedCount);
    }

    @Test
    public void parseCallable_perCourseWarnings_ignored() {
        Map<String, Object> c = course();
        c.put("warnings", Collections.singletonList("x"));
        assertEquals(1, RecognitionParser.parseCallable(envelope(response(Collections.singletonList(c))), 1)
                .courses.size());
    }

    private static String repeat(char ch, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, ch);
        return new String(chars);
    }
}
