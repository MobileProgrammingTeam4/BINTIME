package com.team4.bintime.data.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** [BT-026] 병합: 완전 중복, 같은 이름 다른 시간대, null 건물 보완, 정규화, 7개 상한, 0개. */
public class RecognitionMergerTest {

    private static RecognizedCourse c(String name, int day, int start, int end, String building, String room,
                                      double confidence, int image) {
        return new RecognizedCourse(name, day, start, end, building, room, confidence, image);
    }

    private static List<RecognitionMerger.MergedCourse> merge(RecognizedCourse... courses) {
        return RecognitionMerger.merge(new RecognitionResult(Arrays.asList(courses), Collections.emptyList(), 0, 5));
    }

    @Test
    public void exactDuplicateAcrossTwoImages_mergedWithMaxConfidenceAndBothSources() {
        List<RecognitionMerger.MergedCourse> merged = merge(
                c("자료구조", 3, 780, 870, "미래관", "407호", 0.88, 0),
                c("자료구조", 3, 780, 870, "미래관", "407호", 0.81, 1));
        assertEquals(1, merged.size());
        RecognitionMerger.MergedCourse course = merged.get(0);
        assertEquals("자료구조", course.name);
        assertEquals("미래관 407호", course.classroom);
        assertEquals(1, course.slots.size());
        RecognitionMerger.MergedSlot slot = course.slots.get(0);
        assertEquals(0.88, slot.confidence, 1e-9);
        assertEquals(Arrays.asList(0, 1), slot.sourceImages);
        assertEquals(2, slot.mergedCount);
    }

    @Test
    public void duplicate_nullBuildingFilledFromOtherSide() {
        List<RecognitionMerger.MergedCourse> merged = merge(
                c("자료구조", 3, 780, 870, null, "407호", 0.95, 0),
                c("자료구조", 3, 780, 870, "미래관", null, 0.60, 1));
        assertEquals("미래관 407호", merged.get(0).classroom);
        assertEquals(0.95, merged.get(0).slots.get(0).confidence, 1e-9);
    }

    @Test
    public void duplicate_conflictingRoom_usesHigherConfidence() {
        List<RecognitionMerger.MergedCourse> merged = merge(
                c("자료구조", 3, 780, 870, "미래관", "407호", 0.70, 0),
                c("자료구조", 3, 780, 870, "미래관", "401호", 0.90, 1));
        assertEquals("미래관 401호", merged.get(0).classroom);
    }

    @Test
    public void nameNormalization_ignoresSpacesAndCase() {
        List<RecognitionMerger.MergedCourse> merged = merge(
                c("Data  Structure", 3, 780, 870, null, null, 0.7, 0),
                c("data structure", 3, 780, 870, null, null, 0.9, 1),
                c("DataStructure ", 3, 780, 870, null, null, 0.8, 2));
        assertEquals(1, merged.size());
        assertEquals("data structure", merged.get(0).name); // 가장 높은 신뢰도의 표기
        assertEquals(3, merged.get(0).slots.get(0).mergedCount);
        assertEquals("datastructure", RecognitionMerger.normalize(" Data\tStructure "));
    }

    @Test
    public void sameNameDifferentTimes_groupedIntoOneCourse() {
        List<RecognitionMerger.MergedCourse> merged = merge(
                c("미적분학", 4, 540, 630, "공학관", "201호", 0.9, 0),
                c("미적분학", 2, 540, 630, "공학관", "201호", 0.9, 0));
        assertEquals(1, merged.size());
        assertEquals(2, merged.get(0).slots.size());
        assertEquals(2, merged.get(0).slots.get(0).dayOfWeek); // 요일 순
        assertEquals(4, merged.get(0).slots.get(1).dayOfWeek);
    }

    @Test
    public void sameNameDifferentRooms_stayAsSeparateCourses() {
        // 저장 모델은 수업당 강의실 하나라서 둘 다 값이 있고 다르면 나눈다. 한쪽이 null 이면 묶는다.
        List<RecognitionMerger.MergedCourse> merged = merge(
                c("물리학", 1, 540, 630, "과학관", "101호", 0.9, 0),
                c("물리학", 3, 540, 630, "과학관", "실험실", 0.9, 0),
                c("물리학", 5, 540, 630, null, null, 0.9, 0));
        assertEquals(2, merged.size());
        assertEquals(2, merged.get(0).slots.size()); // 월 + 금(강의실 없음)
        assertEquals("과학관 101호", merged.get(0).classroom);
        assertEquals("과학관 실험실", merged.get(1).classroom);
    }

    @Test
    public void moreThanSevenSlots_splitIntoNextCourse() {
        List<RecognizedCourse> many = new ArrayList<>();
        for (int i = 0; i < 9; i++) many.add(c("세미나", 1 + (i % 7), 540 + 60 * (i / 7), 570 + 60 * (i / 7),
                null, null, 0.9, 0));
        List<RecognitionMerger.MergedCourse> merged = RecognitionMerger.merge(
                new RecognitionResult(many, Collections.emptyList(), 0, 1));
        assertEquals(2, merged.size());
        assertEquals(7, merged.get(0).slots.size());
        assertEquals(2, merged.get(1).slots.size());
    }

    @Test
    public void differentCoursesAtSameTime_areNotMerged() {
        List<RecognitionMerger.MergedCourse> merged = merge(
                c("자료구조", 3, 780, 870, null, null, 0.9, 0),
                c("운영체제", 3, 780, 870, null, null, 0.9, 1));
        assertEquals(2, merged.size()); // 충돌은 RecognitionReview 가 표시
    }

    @Test
    public void weekendCourses_areKept() {
        List<RecognitionMerger.MergedCourse> merged = merge(
                c("토요 특강", 6, 600, 720, null, null, 0.9, 0),
                c("일요 세미나", 7, 600, 660, null, null, 0.9, 0));
        assertEquals(2, merged.size());
        assertEquals(6, merged.get(0).slots.get(0).dayOfWeek);
        assertEquals(7, merged.get(1).slots.get(0).dayOfWeek);
    }

    @Test
    public void longNames_areNotTruncated() {
        String longName = "아주아주아주아주아주아주아주아주아주아주긴수업이름입니다요요요";
        assertEquals(31, longName.length());
        assertEquals(longName, merge(c(longName, 1, 540, 600, null, null, 0.9, 0)).get(0).name);
    }

    @Test
    public void classroomFormatting() {
        assertEquals("공학관 302호", RecognitionMerger.classroom(" 공학관 ", "302호"));
        assertEquals("공학관", RecognitionMerger.classroom("공학관", null));
        assertEquals("302호", RecognitionMerger.classroom(null, "302호"));
        assertNull(RecognitionMerger.classroom(null, null));
        assertNull(RecognitionMerger.classroom(" ", ""));
    }

    @Test
    public void zeroCourses_andNull_giveEmptyList() {
        assertTrue(merge().isEmpty());
        assertTrue(RecognitionMerger.merge(null).isEmpty());
    }

    @Test
    public void serverMock_mergesToThreeCourses() {
        RecognitionResult mock = RecognitionParser.parse(MockTimetableRecognitionRepository.serverMock(2), 2);
        List<RecognitionMerger.MergedCourse> merged = RecognitionMerger.merge(mock);
        assertEquals(3, merged.size());
        assertEquals("컴퓨터 구조론", merged.get(0).name);
        assertEquals("자료구조", merged.get(1).name);
        assertEquals(Arrays.asList(0, 1), merged.get(1).slots.get(0).sourceImages);
        assertEquals("확률과 통계", merged.get(2).name);
        assertNull(merged.get(2).classroom);
        assertEquals(0.52, merged.get(2).slots.get(0).confidence, 1e-9);
    }
}
