package com.team4.bintime.data.availability;

import com.team4.bintime.data.model.TimeSlot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * [BT-016] FreeCells — 공강 선택 원본(요일별 48칸 마스크) 변환·수업 겹침 제거·매칭 대상 계산.
 */
public class FreeCellsTest {

    private static final int MON = 1;
    private static final int TUE = 2;
    private static final int SUN = 7;

    private static TimeSlot slot(int day, int start, int end) {
        return new TimeSlot(day, start, end);
    }

    @Test
    public void fromSlots_setsCellBitsPerDay() {
        long[] m = FreeCells.fromSlots(Arrays.asList(slot(MON, 540, 600), slot(SUN, 1410, 1440)));
        assertEquals((1L << 18) | (1L << 19), m[0]);
        assertEquals(1L << 47, m[6]);
        assertEquals(0L, m[1]);
    }

    @Test
    public void fromSlots_nullOrEmpty_isEmpty() {
        assertArrayEquals(FreeCells.empty(), FreeCells.fromSlots(null));
        assertArrayEquals(FreeCells.empty(), FreeCells.fromSlots(Collections.<TimeSlot>emptyList()));
    }

    @Test
    public void fromSlots_rejectsOffGridAndInvalid() {
        List<List<TimeSlot>> bad = Arrays.asList(
                Collections.singletonList(slot(MON, 545, 600)),   // 30분 경계 아님
                Collections.singletonList(slot(MON, 540, 615)),
                Collections.singletonList(slot(0, 540, 600)),     // 요일 범위
                Collections.singletonList(slot(8, 540, 600)),
                Collections.singletonList(slot(MON, 600, 600)),   // 시작 = 끝
                Collections.singletonList(slot(MON, 1410, 1470)), // 1440 초과
                Collections.singletonList((TimeSlot) null));
        for (List<TimeSlot> slots : bad) {
            try {
                FreeCells.fromSlots(slots);
                fail("expected IllegalArgumentException for " + slots);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void toSlots_mergesAdjacentCells_andKeeps30MinuteSelection() {
        long[] m = FreeCells.fromSlots(Arrays.asList(
                slot(TUE, 600, 630), slot(MON, 540, 570), slot(MON, 570, 600), slot(MON, 660, 690)));
        assertEquals(Arrays.asList(slot(MON, 540, 600), slot(MON, 660, 690), slot(TUE, 600, 630)),
                FreeCells.toSlots(m));
    }

    @Test
    public void roundTrip_throughFirestoreList() {
        long[] m = FreeCells.fromSlots(Arrays.asList(slot(MON, 0, 1440), slot(SUN, 600, 660)));
        List<Long> list = FreeCells.toList(m);
        assertEquals(7, list.size());
        assertEquals(Long.valueOf(FreeCells.FULL_DAY), list.get(0));
        assertArrayEquals(m, FreeCells.fromList(list));
    }

    @Test
    public void fromList_missingField_isEmpty_malformed_isNull() {
        assertArrayEquals(FreeCells.empty(), FreeCells.fromList(null));
        assertNull(FreeCells.fromList("x"));
        assertNull(FreeCells.fromList(Arrays.asList(0L, 0L, 0L)));                    // 길이 7 아님
        assertNull(FreeCells.fromList(Arrays.asList(0L, 0L, 0L, 0L, 0L, 0L, -1L)));  // 음수
        assertNull(FreeCells.fromList(Arrays.asList(0L, 0L, 0L, 0L, 0L, 0L, 1L << 48))); // 48칸 초과
        assertNull(FreeCells.fromList(Arrays.asList(0L, 0L, 0L, 0L, 0L, 0L, 1.5)));   // 소수
        List<Object> ints = new ArrayList<>(Arrays.<Object>asList(1, 0, 0, 0, 0, 0, 0));
        assertEquals(1L, FreeCells.fromList(ints)[0]);
    }

    @Test
    public void keepSelectable_removesCellsTouchedByCourses() {
        long[] m = FreeCells.fromSlots(Collections.singletonList(slot(MON, 540, 720))); // 09:00~12:00
        // 10:00~10:15 수업 → 10:00 칸 불가(1분이라도 겹치면 제외)
        long[] kept = FreeCells.keepSelectable(m, Collections.singletonList(slot(MON, 600, 615)));
        assertEquals(Arrays.asList(slot(MON, 540, 600), slot(MON, 630, 720)), FreeCells.toSlots(kept));
        assertFalse(FreeCells.isSelectable(m, Collections.singletonList(slot(MON, 600, 615))));
        assertTrue(FreeCells.isSelectable(m, Collections.singletonList(slot(TUE, 600, 615))));
        assertTrue(FreeCells.isSelectable(m, Collections.singletonList(slot(MON, 720, 780)))); // 맞닿음
    }

    @Test
    public void matchable_onlyMergedBlocksOf60MinutesOrMore() {
        long[] m = FreeCells.fromSlots(Arrays.asList(
                slot(MON, 540, 570),   // 30분 → 제외
                slot(MON, 600, 630), slot(MON, 630, 660), // 붙은 칸 병합 60분 → 포함
                slot(TUE, 600, 690))); // 90분 → 포함
        assertEquals(Arrays.asList(slot(MON, 600, 660), slot(TUE, 600, 690)), FreeCells.matchable(m));
    }

    @Test
    public void availability_documentRoundTrip() {
        long[] m = FreeCells.fromSlots(Arrays.asList(slot(MON, 540, 570), slot(TUE, 600, 720)));
        Availability a = Availability.fromCells(m, 4);
        assertEquals(Collections.singletonList(slot(TUE, 600, 720)), a.getSlots());
        assertEquals(4, a.getSourceRevision());
        java.util.Map<String, Object> doc = a.toDocument();
        assertEquals(2, doc.size());
        assertFalse(doc.containsKey("updatedAt"));
        Availability back = Availability.fromDocument(doc);
        assertEquals(a.getSlots(), back.getSlots());
        assertEquals(4, back.getSourceRevision());
        assertEquals("availability/u1", Availability.path("u1"));
    }

    @Test
    public void availability_malformedDocument_isNull() {
        java.util.Map<String, Object> doc = new java.util.HashMap<>();
        doc.put("slots", "x");
        doc.put("sourceRevision", 1L);
        assertNull(Availability.fromDocument(doc));
        assertNull(Availability.fromDocument(null));
    }

    @Test
    public void maxSlotsCase_alternatingCells() {
        // 2칸 선택 + 1칸 비움 반복 → 하루 16개, 월~금 80개 (상한 40 초과 가능)
        long mask = 0;
        for (int cell = 0; cell + 1 < 48; cell += 3) mask |= 3L << cell;
        long[] m = FreeCells.empty();
        for (int d = 0; d < 5; d++) m[d] = mask;
        assertEquals(80, FreeCells.matchable(m).size());
    }
}
