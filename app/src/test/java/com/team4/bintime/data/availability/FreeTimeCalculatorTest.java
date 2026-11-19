package com.team4.bintime.data.availability;

import com.team4.bintime.data.model.TimeSlot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * [BT-016] FreeTimeCalculator — 공강 산출, 교집합, 최소 겹침 판정.
 */
public class FreeTimeCalculatorTest {

    private static final int MON = 1;
    private static final int TUE = 2;

    private static TimeSlot slot(int day, int start, int end) {
        return new TimeSlot(day, start, end);
    }

    private static boolean cell(long mask, int slot) {
        return (mask & (1L << slot)) != 0;
    }

    // ── normalize ───────────────────────────────────────

    @Test
    public void normalize_nullAndInvalidDropped() {
        assertEquals(Collections.emptyList(), FreeTimeCalculator.normalize(null));
        List<TimeSlot> input = Arrays.asList(
                null,
                slot(0, 600, 660),
                slot(8, 600, 660),
                slot(MON, 600, 600),
                slot(MON, 660, 600),
                slot(MON, -30, 60),
                slot(MON, 1400, 1470),
                slot(MON, 540, 600));
        assertEquals(Collections.singletonList(slot(MON, 540, 600)), FreeTimeCalculator.normalize(input));
    }

    @Test
    public void normalize_mergesOverlappingAndTouching_sortsByDay() {
        List<TimeSlot> input = Arrays.asList(
                slot(TUE, 600, 630),
                slot(MON, 630, 660),
                slot(MON, 600, 630),
                slot(MON, 650, 700));
        assertEquals(Arrays.asList(slot(MON, 600, 700), slot(TUE, 600, 630)),
                FreeTimeCalculator.normalize(input));
    }

    @Test
    public void normalize_doesNotMutateInput() {
        TimeSlot a = slot(MON, 600, 630);
        TimeSlot b = slot(MON, 630, 660);
        List<TimeSlot> input = new ArrayList<>(Arrays.asList(b, a));
        FreeTimeCalculator.normalize(input);
        assertEquals(Arrays.asList(b, a), input);
        assertEquals(slot(MON, 600, 630), a);
        assertEquals(slot(MON, 630, 660), b);
    }

    // ── gapsBetweenCourses ──────────────────────────────

    @Test
    public void gaps_noCourses_none() {
        assertTrue(FreeTimeCalculator.gapsBetweenCourses(Collections.emptyList()).isEmpty());
    }

    @Test
    public void gaps_singleCourse_none() {
        assertTrue(FreeTimeCalculator.gapsBetweenCourses(
                Collections.singletonList(slot(MON, 600, 690))).isEmpty());
    }

    @Test
    public void gaps_backToBack_startEqualsEnd_none() {
        assertTrue(FreeTimeCalculator.gapsBetweenCourses(
                Arrays.asList(slot(MON, 600, 660), slot(MON, 660, 720))).isEmpty());
    }

    @Test
    public void gaps_betweenCourses_unsortedInput() {
        List<TimeSlot> courses = Arrays.asList(slot(MON, 780, 840), slot(MON, 600, 690));
        assertEquals(Collections.singletonList(slot(MON, 690, 780)),
                FreeTimeCalculator.gapsBetweenCourses(courses));
    }

    @Test
    public void gaps_overlappingCoursesMergedFirst() {
        List<TimeSlot> courses = Arrays.asList(
                slot(MON, 600, 690), slot(MON, 660, 720), slot(MON, 780, 840));
        assertEquals(Collections.singletonList(slot(MON, 720, 780)),
                FreeTimeCalculator.gapsBetweenCourses(courses));
    }

    @Test
    public void gaps_unalignedMinutesKept() {
        List<TimeSlot> courses = Arrays.asList(slot(MON, 540, 615), slot(MON, 700, 760));
        assertEquals(Collections.singletonList(slot(MON, 615, 700)),
                FreeTimeCalculator.gapsBetweenCourses(courses));
    }

    @Test
    public void gaps_daysIndependent() {
        List<TimeSlot> courses = Arrays.asList(
                slot(MON, 600, 660), slot(TUE, 720, 780), slot(MON, 1380, 1440));
        assertEquals(Collections.singletonList(slot(MON, 660, 1380)),
                FreeTimeCalculator.gapsBetweenCourses(courses));
    }

    // ── selectableCells ─────────────────────────────────

    @Test
    public void selectable_noCourses_allCells() {
        assertEquals((1L << 48) - 1, FreeTimeCalculator.selectableCells(null, MON));
    }

    @Test
    public void selectable_courseEndingAt1015_blocks1000_opens1030() {
        long mask = FreeTimeCalculator.selectableCells(
                Collections.singletonList(slot(MON, 540, 615)), MON);
        assertTrue(cell(mask, 17));   // 08:30
        assertFalse(cell(mask, 18));  // 09:00
        assertFalse(cell(mask, 19));  // 09:30
        assertFalse(cell(mask, 20));  // 10:00 — 10:15까지 수업
        assertTrue(cell(mask, 21));   // 10:30
    }

    @Test
    public void selectable_courseEndingOnBoundary_nextCellOpen() {
        long mask = FreeTimeCalculator.selectableCells(
                Collections.singletonList(slot(MON, 600, 690)), MON);
        assertFalse(cell(mask, 22));  // 11:00
        assertTrue(cell(mask, 23));   // 11:30
        assertTrue(cell(mask, 19));   // 09:30
    }

    @Test
    public void selectable_otherDayIgnored_andLastCell() {
        List<TimeSlot> courses = Arrays.asList(slot(TUE, 600, 690), slot(MON, 1410, 1440));
        long mask = FreeTimeCalculator.selectableCells(courses, MON);
        assertTrue(cell(mask, 20));
        assertFalse(cell(mask, 47));
    }

    @Test(expected = IllegalArgumentException.class)
    public void selectable_invalidDay_throws() {
        FreeTimeCalculator.selectableCells(null, 0);
    }

    // ── intersect ───────────────────────────────────────

    @Test
    public void intersect_touching_none() {
        assertTrue(FreeTimeCalculator.intersect(
                Collections.singletonList(slot(MON, 600, 660)),
                Collections.singletonList(slot(MON, 660, 720))).isEmpty());
    }

    @Test
    public void intersect_differentDay_none() {
        assertTrue(FreeTimeCalculator.intersect(
                Collections.singletonList(slot(MON, 600, 720)),
                Collections.singletonList(slot(TUE, 600, 720))).isEmpty());
    }

    @Test
    public void intersect_contained_andIdentical() {
        assertEquals(Collections.singletonList(slot(MON, 630, 690)), FreeTimeCalculator.intersect(
                Collections.singletonList(slot(MON, 600, 720)),
                Collections.singletonList(slot(MON, 630, 690))));
        assertEquals(Collections.singletonList(slot(MON, 600, 720)), FreeTimeCalculator.intersect(
                Collections.singletonList(slot(MON, 600, 720)),
                Collections.singletonList(slot(MON, 600, 720))));
    }

    @Test
    public void intersect_multiplePerDay_andAcrossDays() {
        List<TimeSlot> mine = Arrays.asList(slot(MON, 540, 720), slot(MON, 780, 900), slot(TUE, 600, 660));
        List<TimeSlot> theirs = Arrays.asList(slot(MON, 600, 840), slot(TUE, 630, 700));
        assertEquals(Arrays.asList(slot(MON, 600, 720), slot(MON, 780, 840), slot(TUE, 630, 660)),
                FreeTimeCalculator.intersect(mine, theirs));
    }

    @Test
    public void intersect_dayBoundaries_0And1440() {
        assertEquals(Arrays.asList(slot(MON, 0, 60), slot(MON, 1380, 1440)), FreeTimeCalculator.intersect(
                Arrays.asList(slot(MON, 0, 90), slot(MON, 1350, 1440)),
                Arrays.asList(slot(MON, 0, 60), slot(MON, 1380, 1440))));
    }

    // ── 최소 겹침 ───────────────────────────────────────

    @Test
    public void meetsMinOverlap_boundaryTable() {
        int[] overlaps = {0, 30, 59, 60, 89, 90, 119, 120, 149, 150, 179, 180};
        int[] filters = {60, 90, 120, 150, 180};
        for (int min : filters) {
            for (int overlap : overlaps) {
                assertEquals("overlap=" + overlap + ", min=" + min,
                        overlap >= min, FreeTimeCalculator.meetsMinOverlap(overlap, min));
            }
        }
    }

    @Test
    public void meetsMinOverlap_59vs60() {
        assertFalse(FreeTimeCalculator.meetsMinOverlap(59, 60));
        assertTrue(FreeTimeCalculator.meetsMinOverlap(60, 60));
    }

    @Test
    public void invalidMinOverlap_throws() {
        for (int invalid : new int[]{0, 30, 45, 200}) {
            try {
                FreeTimeCalculator.meetsMinOverlap(120, invalid);
                fail("meetsMinOverlap accepted " + invalid);
            } catch (IllegalArgumentException expected) {
                // ok
            }
            try {
                FreeTimeCalculator.matchableOverlaps(null, null, invalid);
                fail("matchableOverlaps accepted " + invalid);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void matchableOverlaps_59Excluded_60Included() {
        List<TimeSlot> mine = Collections.singletonList(slot(MON, 600, 900));
        assertTrue(FreeTimeCalculator.matchableOverlaps(
                mine, Collections.singletonList(slot(MON, 841, 1000)), 60).isEmpty());
        assertEquals(Collections.singletonList(slot(MON, 840, 900)), FreeTimeCalculator.matchableOverlaps(
                mine, Collections.singletonList(slot(MON, 840, 1000)), 60));
    }

    @Test
    public void matchableOverlaps_filterBoundaries() {
        List<TimeSlot> mine = Collections.singletonList(slot(MON, 0, 1440));
        int[] filters = {60, 90, 120, 150, 180};
        for (int min : filters) {
            assertTrue(FreeTimeCalculator.matchableOverlaps(
                    mine, Collections.singletonList(slot(MON, 600, 600 + min - 1)), min).isEmpty());
            assertEquals(1, FreeTimeCalculator.matchableOverlaps(
                    mine, Collections.singletonList(slot(MON, 600, 600 + min)), min).size());
        }
    }

    // ── 수용 기준 ①: 30분 선택 허용, 60분 미만 매칭 제외 ──

    @Test
    public void matchableFreeSlots_single30MinCell_excluded() {
        List<TimeSlot> free = SlotGrid.cellsToSlots(MON, 1L << 20);
        assertEquals(1, free.size());
        assertTrue(FreeTimeCalculator.matchableFreeSlots(free).isEmpty());
    }

    @Test
    public void matchableFreeSlots_twoAdjacent30MinCells_included() {
        List<TimeSlot> free = Arrays.asList(slot(MON, 600, 630), slot(MON, 630, 660));
        assertEquals(Collections.singletonList(slot(MON, 600, 660)),
                FreeTimeCalculator.matchableFreeSlots(free));
    }

    @Test
    public void matchableFreeSlots_twoSeparated30MinCells_excluded() {
        List<TimeSlot> free = SlotGrid.cellsToSlots(MON, (1L << 20) | (1L << 22));
        assertTrue(FreeTimeCalculator.matchableFreeSlots(free).isEmpty());
    }

    @Test
    public void matchableFreeSlots_59Excluded_60Included() {
        assertTrue(FreeTimeCalculator.matchableFreeSlots(
                Collections.singletonList(slot(MON, 601, 660))).isEmpty());
        assertEquals(1, FreeTimeCalculator.matchableFreeSlots(
                Collections.singletonList(slot(MON, 600, 660))).size());
    }

    // ── [BT-036] 경계값 보강 ─────────────────────────────

    private static final int SAT = 6;
    private static final int SUN = 7;

    @Test
    public void meetsMinOverlap_61_andMinPlusOne() {
        assertTrue(FreeTimeCalculator.meetsMinOverlap(61, 60));
        for (int min : new int[]{60, 90, 120, 150, 180}) {
            assertFalse("min-1, min=" + min, FreeTimeCalculator.meetsMinOverlap(min - 1, min));
            assertTrue("min, min=" + min, FreeTimeCalculator.meetsMinOverlap(min, min));
            assertTrue("min+1, min=" + min, FreeTimeCalculator.meetsMinOverlap(min + 1, min));
        }
    }

    @Test
    public void matchableOverlaps_59_60_61() {
        List<TimeSlot> mine = Collections.singletonList(slot(MON, 600, 900));
        assertTrue(FreeTimeCalculator.matchableOverlaps(
                mine, Collections.singletonList(slot(MON, 841, 1000)), 60).isEmpty());       // 59분
        assertEquals(Collections.singletonList(slot(MON, 840, 900)), FreeTimeCalculator.matchableOverlaps(
                mine, Collections.singletonList(slot(MON, 840, 1000)), 60));                // 60분
        assertEquals(Collections.singletonList(slot(MON, 839, 900)), FreeTimeCalculator.matchableOverlaps(
                mine, Collections.singletonList(slot(MON, 839, 1000)), 60));                // 61분
    }

    @Test
    public void matchableOverlaps_filterMinPlusOne() {
        List<TimeSlot> mine = Collections.singletonList(slot(MON, 0, 1440));
        for (int min : new int[]{60, 90, 120, 150, 180}) {
            assertEquals(Collections.singletonList(slot(MON, 600, 600 + min + 1)),
                    FreeTimeCalculator.matchableOverlaps(
                            mine, Collections.singletonList(slot(MON, 600, 600 + min + 1)), min));
        }
    }

    @Test
    public void matchableOverlaps_adjacentCellsMergedBeforeIntersect_gapNotBridged() {
        // 30분 칸 두 개가 맞닿아 있으면 합친 뒤 60분 교집합
        assertEquals(Collections.singletonList(slot(MON, 600, 660)), FreeTimeCalculator.matchableOverlaps(
                Arrays.asList(slot(MON, 600, 630), slot(MON, 630, 660)),
                Collections.singletonList(slot(MON, 600, 660)), 60));
        // 사이에 30분 빈칸이 있으면 30분 + 30분이라 60분 필터를 통과하지 못한다
        List<TimeSlot> split = Arrays.asList(slot(MON, 600, 630), slot(MON, 660, 690));
        List<TimeSlot> theirs = Collections.singletonList(slot(MON, 600, 690));
        assertEquals(Arrays.asList(slot(MON, 600, 630), slot(MON, 660, 690)),
                FreeTimeCalculator.intersect(split, theirs));
        assertTrue(FreeTimeCalculator.matchableOverlaps(split, theirs, 60).isEmpty());
    }

    @Test
    public void intersect_sunday_lastHour() {
        assertEquals(Collections.singletonList(slot(SUN, 1380, 1440)), FreeTimeCalculator.matchableOverlaps(
                Collections.singletonList(slot(SUN, 1380, 1440)),
                Collections.singletonList(slot(SUN, 1320, 1440)), 60));
    }

    @Test
    public void intersect_doesNotWrapAcrossDaysOrWeek() {
        // 같은 분이라도 요일이 다르면 0분
        assertTrue(FreeTimeCalculator.intersect(
                Collections.singletonList(slot(SAT, 600, 720)),
                Collections.singletonList(slot(SUN, 600, 720))).isEmpty());
        // 일 23:00~24:00 과 월 00:00~01:00 은 이어지지 않는다
        assertEquals(Collections.singletonList(slot(SUN, 1410, 1440)), FreeTimeCalculator.intersect(
                Arrays.asList(slot(SUN, 1380, 1440), slot(MON, 0, 60)),
                Collections.singletonList(slot(SUN, 1410, 1440))));
        assertTrue(FreeTimeCalculator.matchableOverlaps(
                Collections.singletonList(slot(SUN, 1410, 1440)),
                Arrays.asList(slot(SUN, 1380, 1440), slot(MON, 0, 60)), 60).isEmpty());
        // 월~일 전 구간 vs 일요일만
        List<TimeSlot> week = new ArrayList<>();
        for (int d = MON; d <= SUN; d++) week.add(slot(d, 0, 1440));
        assertEquals(Collections.singletonList(slot(SUN, 0, 1440)),
                FreeTimeCalculator.intersect(week, Collections.singletonList(slot(SUN, 0, 1440))));
    }

    @Test
    public void selectable_sunday_lastCell() {
        long mask = FreeTimeCalculator.selectableCells(
                Collections.singletonList(slot(SUN, 1410, 1440)), SUN);
        assertFalse(cell(mask, 47));
        assertTrue(cell(mask, 46));
        assertTrue(cell(FreeTimeCalculator.selectableCells(
                Collections.singletonList(slot(SUN, 1410, 1440)), SAT), 47));
    }

    @Test(expected = IllegalArgumentException.class)
    public void selectable_day8_throws() {
        FreeTimeCalculator.selectableCells(null, 8);
    }

    @Test
    public void selectable_midnightStartCourse_blocksFirstCellOnly() {
        long mask = FreeTimeCalculator.selectableCells(Collections.singletonList(slot(MON, 0, 30)), MON);
        assertFalse(cell(mask, 0));
        assertTrue(cell(mask, 1));
    }
}
