package com.team4.bintime.data.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.recognition.RecognitionReview.Flag;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** [BT-026] 검수 판정: 충돌(맞닿음 허용), 낮은 신뢰도, 30자 초과, 주말, 강의실 없음, 7개 초과, 0개. */
public class RecognitionReviewTest {

    private static RecognitionReview.Row row(String key, String name, String room, int day, int start, int end,
                                             double confidence) {
        return new RecognitionReview.Row(key, name, room, day, start, end, confidence, 1);
    }

    private static RecognitionReview.Row ok(String key, int day, int start, int end) {
        return row(key, "수업" + key, "공학관 101호", day, start, end, 0.9);
    }

    @Test
    public void differentCoursesOverlapping_bothMarkedConflict_andConfirmBlocked() {
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(ok("a", 3, 780, 870), ok("b", 3, 840, 900)));
        assertTrue(flags.get(0).contains(Flag.CONFLICT));
        assertTrue(flags.get(1).contains(Flag.CONFLICT));
        assertFalse(RecognitionReview.canConfirm(flags));
        assertEquals(2, RecognitionReview.blockingCount(flags));
    }

    @Test
    public void touchingEndAndStart_isNotConflict() {
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(ok("a", 3, 780, 870), ok("b", 3, 870, 960)));
        assertFalse(flags.get(0).contains(Flag.CONFLICT));
        assertFalse(flags.get(1).contains(Flag.CONFLICT));
        assertTrue(RecognitionReview.canConfirm(flags));
    }

    @Test
    public void sameTimeOnDifferentDays_isNotConflict() {
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(ok("a", 3, 780, 870), ok("b", 4, 780, 870)));
        assertTrue(RecognitionReview.canConfirm(flags));
    }

    @Test
    public void slotsOfSameCourseOverlapping_isConflictToo() {
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(ok("a", 1, 540, 630), ok("a", 1, 600, 660)));
        assertTrue(flags.get(0).contains(Flag.CONFLICT));
    }

    @Test
    public void lowConfidence_isHighlightedButNotBlocking() {
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(
                row("a", "확률과 통계", "본관", 4, 540, 630, 0.52),
                row("b", "자료구조", "본관", 3, 540, 630, RecognitionReview.LOW_CONFIDENCE),
                row("c", "직접 추가", "본관", 2, 540, 630, Double.NaN)));
        assertTrue(flags.get(0).contains(Flag.LOW_CONFIDENCE));
        assertFalse(flags.get(1).contains(Flag.LOW_CONFIDENCE)); // 경계값은 낮지 않음
        assertFalse(flags.get(2).contains(Flag.LOW_CONFIDENCE)); // 인식 행이 아님
        assertTrue(RecognitionReview.canConfirm(flags));
    }

    @Test
    public void overThirtyChars_needsEdit_andBlocks() {
        String thirty = "가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나";
        assertEquals(30, thirty.length());
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(
                row("a", thirty, thirty, 1, 540, 600, 0.9),
                row("b", thirty + "다", "본관", 2, 540, 600, 0.9),
                row("c", "수업", thirty + "다", 3, 540, 600, 0.9)));
        assertTrue(flags.get(0).isEmpty());
        assertEquals(EnumSet.of(Flag.NEEDS_EDIT_NAME_LENGTH), flags.get(1));
        assertEquals(EnumSet.of(Flag.NEEDS_EDIT_CLASSROOM_LENGTH), flags.get(2));
        assertFalse(RecognitionReview.canConfirm(flags));
        assertEquals(2, RecognitionReview.blockingCount(flags));
    }

    @Test
    public void emptyNameAndBadTime_needEdit() {
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(
                row("a", "  ", "본관", 1, 540, 600, 0.9),
                row("b", "수업", "본관", 1, 600, 600, 0.9),
                row("c", "수업", "본관", 0, 540, 600, 0.9),
                row("d", "수업", "본관", 2, -1, 600, 0.9)));
        assertTrue(flags.get(0).contains(Flag.NEEDS_EDIT_NAME_EMPTY));
        assertTrue(flags.get(1).contains(Flag.NEEDS_EDIT_TIME));
        assertTrue(flags.get(2).contains(Flag.NEEDS_EDIT_TIME));
        assertTrue(flags.get(3).contains(Flag.NEEDS_EDIT_TIME));
        assertFalse(flags.get(1).contains(Flag.CONFLICT)); // 시간이 틀린 행은 충돌 판정에서 뺀다
    }

    @Test
    public void weekend_isInfoOnly() {
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(ok("a", 6, 600, 720), ok("b", 7, 600, 720)));
        assertEquals(EnumSet.of(Flag.WEEKEND), flags.get(0));
        assertEquals(EnumSet.of(Flag.WEEKEND), flags.get(1)); // 토·일 같은 시간은 충돌 아님
        assertTrue(RecognitionReview.canConfirm(flags));
    }

    @Test
    public void weekendSameDayOverlap_isConflict() {
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(ok("a", 6, 600, 720), ok("b", 6, 660, 780)));
        assertTrue(flags.get(0).contains(Flag.CONFLICT));
    }

    @Test
    public void missingClassroom_andMergedDuplicate_areInfoOnly() {
        List<Set<Flag>> flags = RecognitionReview.check(Collections.singletonList(
                new RecognitionReview.Row("a", "자료구조", null, 3, 780, 870, 0.88, 2)));
        assertEquals(EnumSet.of(Flag.NO_CLASSROOM, Flag.MERGED_DUPLICATE), flags.get(0));
        assertTrue(RecognitionReview.canConfirm(flags));
    }

    @Test
    public void moreThanSevenSlotsInOneCourse_needsEdit() {
        RecognitionReview.Row[] rows = new RecognitionReview.Row[8];
        for (int i = 0; i < 8; i++) rows[i] = ok("a", 1 + (i % 7), 540 + 120 * (i / 7), 600 + 120 * (i / 7));
        List<Set<Flag>> flags = RecognitionReview.check(Arrays.asList(rows));
        assertTrue(flags.get(0).contains(Flag.NEEDS_EDIT_TOO_MANY_SLOTS));
    }

    @Test
    public void zeroRows_cannotConfirm() {
        List<Set<Flag>> flags = RecognitionReview.check(Collections.emptyList());
        assertTrue(flags.isEmpty());
        assertFalse(RecognitionReview.canConfirm(flags));
        assertFalse(RecognitionReview.canConfirm(null));
    }
}
