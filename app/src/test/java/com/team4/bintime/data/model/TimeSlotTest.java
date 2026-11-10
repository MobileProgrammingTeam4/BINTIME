package com.team4.bintime.data.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * [BT-012] TimeSlot 단위 테스트 — 시간 파싱, 슬롯 인덱스, 겹침 계산.
 */
public class TimeSlotTest {

    // ── 생성 및 기본 속성 ────────────────────────────────

    @Test
    public void testBasicProperties() {
        TimeSlot slot = new TimeSlot(1, 540, 630); // 월 09:00~10:30
        assertEquals(1, slot.getDayOfWeek());
        assertEquals(540, slot.getStartMinute());
        assertEquals(630, slot.getEndMinute());
        assertEquals(90, slot.getDurationMinutes());
    }

    // ── dayOfWeek 변환 ──────────────────────────────────

    @Test
    public void testDayLabels() {
        assertEquals("월", new TimeSlot(1, 0, 60).getDayLabel());
        assertEquals("화", new TimeSlot(2, 0, 60).getDayLabel());
        assertEquals("수", new TimeSlot(3, 0, 60).getDayLabel());
        assertEquals("목", new TimeSlot(4, 0, 60).getDayLabel());
        assertEquals("금", new TimeSlot(5, 0, 60).getDayLabel());
        assertEquals("토", new TimeSlot(6, 0, 60).getDayLabel());
        assertEquals("일", new TimeSlot(7, 0, 60).getDayLabel());
    }

    @Test
    public void testDayIndex() {
        assertEquals(0, new TimeSlot(1, 0, 60).getDayIndex()); // 월 → 0
        assertEquals(4, new TimeSlot(5, 0, 60).getDayIndex()); // 금 → 4
    }

    @Test
    public void testDayIndexToDayOfWeek() {
        assertEquals(1, TimeSlot.dayIndexToDayOfWeek(0)); // 0 → 월
        assertEquals(5, TimeSlot.dayIndexToDayOfWeek(4)); // 4 → 금
    }

    @Test
    public void testParseDayOfWeek() {
        assertEquals(1, TimeSlot.parseDayOfWeek("월"));
        assertEquals(1, TimeSlot.parseDayOfWeek("월요일"));
        assertEquals(3, TimeSlot.parseDayOfWeek("수요일"));
        assertEquals(5, TimeSlot.parseDayOfWeek("금"));
        assertEquals(7, TimeSlot.parseDayOfWeek("일요일"));
        assertEquals(-1, TimeSlot.parseDayOfWeek(""));
        assertEquals(-1, TimeSlot.parseDayOfWeek(null));
    }

    // ── 시간 파싱 ────────────────────────────────────────

    @Test
    public void testParseTimeToMinutes() {
        assertEquals(540, TimeSlot.parseTimeToMinutes("09:00"));
        assertEquals(870, TimeSlot.parseTimeToMinutes("14:30"));
        assertEquals(0, TimeSlot.parseTimeToMinutes("00:00"));
        assertEquals(1439, TimeSlot.parseTimeToMinutes("23:59"));
        assertEquals(-1, TimeSlot.parseTimeToMinutes(null));
        assertEquals(-1, TimeSlot.parseTimeToMinutes("invalid"));
        assertEquals(-1, TimeSlot.parseTimeToMinutes("25:00")); // 범위 밖
    }

    @Test
    public void testMinutesToTimeString() {
        assertEquals("09:00", TimeSlot.minutesToTimeString(540));
        assertEquals("14:30", TimeSlot.minutesToTimeString(870));
        assertEquals("00:00", TimeSlot.minutesToTimeString(0));
        assertEquals("23:59", TimeSlot.minutesToTimeString(1439));
    }

    // ── 슬롯 인덱스 계산 ────────────────────────────────

    @Test
    public void testSlotIndex_30MinuteGrid() {
        // 09:00 → 슬롯 0
        TimeSlot s1 = new TimeSlot(1, 540, 570);
        assertEquals(0, s1.getStartSlotIndex());
        assertEquals(1, s1.getEndSlotIndex());

        // 09:30 → 슬롯 1
        TimeSlot s2 = new TimeSlot(1, 570, 600);
        assertEquals(1, s2.getStartSlotIndex());
        assertEquals(2, s2.getEndSlotIndex());

        // 10:00~11:30 → 슬롯 2~5
        TimeSlot s3 = new TimeSlot(1, 600, 690);
        assertEquals(2, s3.getStartSlotIndex());
        assertEquals(5, s3.getEndSlotIndex());

        // 14:00~15:30 → 슬롯 10~13
        TimeSlot s4 = new TimeSlot(3, 840, 930);
        assertEquals(10, s4.getStartSlotIndex());
        assertEquals(13, s4.getEndSlotIndex());
    }

    // ── 겹침 계산 ────────────────────────────────────────

    @Test
    public void testOverlapMinutes_sameDay() {
        TimeSlot a = new TimeSlot(1, 540, 630);  // 월 09:00~10:30
        TimeSlot b = new TimeSlot(1, 600, 720);  // 월 10:00~12:00
        assertEquals(30, a.overlapMinutes(b)); // 10:00~10:30 = 30분
    }

    @Test
    public void testOverlapMinutes_noOverlap() {
        TimeSlot a = new TimeSlot(1, 540, 600);  // 월 09:00~10:00
        TimeSlot b = new TimeSlot(1, 600, 660);  // 월 10:00~11:00
        assertEquals(0, a.overlapMinutes(b)); // [09:00,10:00) vs [10:00,11:00) = 0
    }

    @Test
    public void testOverlapMinutes_differentDay() {
        TimeSlot a = new TimeSlot(1, 540, 630);  // 월
        TimeSlot b = new TimeSlot(2, 540, 630);  // 화
        assertEquals(0, a.overlapMinutes(b)); // 다른 요일 = 0
    }

    @Test
    public void testOverlapMinutes_contained() {
        TimeSlot a = new TimeSlot(1, 540, 720);  // 09:00~12:00
        TimeSlot b = new TimeSlot(1, 600, 660);  // 10:00~11:00
        assertEquals(60, a.overlapMinutes(b));
    }

    @Test
    public void testOverlapMinutes_exact() {
        TimeSlot a = new TimeSlot(1, 600, 720);
        TimeSlot b = new TimeSlot(1, 600, 720);
        assertEquals(120, a.overlapMinutes(b));
    }

    @Test
    public void testOverlaps() {
        TimeSlot a = new TimeSlot(1, 540, 630);
        TimeSlot b = new TimeSlot(1, 600, 720);
        assertTrue(a.overlaps(b));

        TimeSlot c = new TimeSlot(1, 630, 720);
        assertFalse(a.overlaps(c)); // 정확히 경계 = 겹치지 않음
    }

    // ── AGENTS.md 겹침 경계값 시나리오 ──────────────────

    @Test
    public void testOverlapMinutes_boundaryValues() {
        // 0분 겹침
        TimeSlot base = new TimeSlot(1, 600, 660); // 10:00~11:00
        assertEquals(0, base.overlapMinutes(new TimeSlot(1, 660, 720)));

        // 30분 겹침
        assertEquals(30, base.overlapMinutes(new TimeSlot(1, 630, 720)));

        // 59분 겹침
        assertEquals(59, base.overlapMinutes(new TimeSlot(1, 601, 720)));

        // 60분 겹침
        assertEquals(60, base.overlapMinutes(new TimeSlot(1, 600, 720)));

        // 90분
        TimeSlot wide = new TimeSlot(1, 540, 720); // 09:00~12:00
        TimeSlot range = new TimeSlot(1, 570, 720); // 09:30~12:00
        assertEquals(150, wide.overlapMinutes(range)); // 09:30~12:00 = 150분

        // 180분
        TimeSlot full = new TimeSlot(1, 540, 720);
        assertEquals(180, full.overlapMinutes(full));
    }

    // ── 표시 문자열 ──────────────────────────────────────

    @Test
    public void testToDisplayString() {
        TimeSlot slot = new TimeSlot(2, 600, 690); // 화 10:00~11:30
        assertEquals("화 10:00 ~ 11:30", slot.toDisplayString());
    }

    // ── equals / hashCode ──────────────────────────────

    @Test
    public void testEquality() {
        TimeSlot a = new TimeSlot(1, 540, 630);
        TimeSlot b = new TimeSlot(1, 540, 630);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
