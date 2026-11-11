package com.team4.bintime.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * [BT-012] TimeUtils 유틸리티 단위 테스트.
 * 24h↔12h 변환, 슬롯 인덱스, 겹침 계산, 최소 겹침 필터 유효성.
 */
public class TimeUtilsTest {

    // ── 24h → 분 변환 ─────────────────────────────────

    @Test
    public void testParseMinutes() {
        assertEquals(540, TimeUtils.parseMinutes("09:00"));
        assertEquals(870, TimeUtils.parseMinutes("14:30"));
        assertEquals(0, TimeUtils.parseMinutes("00:00"));
        assertEquals(-1, TimeUtils.parseMinutes(null));
        assertEquals(-1, TimeUtils.parseMinutes("abc"));
    }

    @Test
    public void testFormatTime24() {
        assertEquals("09:00", TimeUtils.formatTime24(540));
        assertEquals("14:30", TimeUtils.formatTime24(870));
        assertEquals("00:00", TimeUtils.formatTime24(0));
    }

    // ── 12시간제 ↔ 24시간제 변환 ─────────────────────

    @Test
    public void testFormatTime12() {
        assertEquals("오전 12:00", TimeUtils.formatTime12(0));     // 00:00
        assertEquals("오전 09:00", TimeUtils.formatTime12(540));   // 09:00
        assertEquals("오후 12:00", TimeUtils.formatTime12(720));   // 12:00
        assertEquals("오후 02:00", TimeUtils.formatTime12(840));   // 14:00
        assertEquals("오후 11:30", TimeUtils.formatTime12(1410));  // 23:30
    }

    @Test
    public void testTo24HourString() {
        // 오전 테스트
        assertEquals("00:00", TimeUtils.to24HourString(false, 12, 0));
        assertEquals("01:30", TimeUtils.to24HourString(false, 1, 30));
        assertEquals("10:00", TimeUtils.to24HourString(false, 10, 0));
        assertEquals("11:45", TimeUtils.to24HourString(false, 11, 45));

        // 오후 테스트 (Known Issue: 오후 2시 → 14:00)
        assertEquals("12:00", TimeUtils.to24HourString(true, 12, 0));
        assertEquals("13:00", TimeUtils.to24HourString(true, 1, 0));
        assertEquals("14:00", TimeUtils.to24HourString(true, 2, 0));
        assertEquals("18:30", TimeUtils.to24HourString(true, 6, 30));
        assertEquals("23:55", TimeUtils.to24HourString(true, 11, 55));
    }

    @Test
    public void testIsPm() {
        assertFalse(TimeUtils.isPm("09:00"));
        assertFalse(TimeUtils.isPm("11:59"));
        assertTrue(TimeUtils.isPm("12:00"));
        assertTrue(TimeUtils.isPm("14:30"));
    }

    @Test
    public void testGetHour12() {
        assertEquals(9, TimeUtils.getHour12("09:00"));
        assertEquals(12, TimeUtils.getHour12("12:00"));
        assertEquals(2, TimeUtils.getHour12("14:00"));
        assertEquals(12, TimeUtils.getHour12("00:00"));
    }

    // ── 30분 그리드 슬롯 ─────────────────────────────

    @Test
    public void testMinutesToSlotIndex() {
        assertEquals(0, TimeUtils.minutesToSlotIndex(540));  // 09:00
        assertEquals(1, TimeUtils.minutesToSlotIndex(570));  // 09:30
        assertEquals(2, TimeUtils.minutesToSlotIndex(600));  // 10:00
        assertEquals(10, TimeUtils.minutesToSlotIndex(840)); // 14:00
        assertEquals(17, TimeUtils.minutesToSlotIndex(1050)); // 17:30
    }

    @Test
    public void testSlotIndexToStartMinute() {
        assertEquals(540, TimeUtils.slotIndexToStartMinute(0));   // 슬롯 0 → 09:00
        assertEquals(570, TimeUtils.slotIndexToStartMinute(1));   // 슬롯 1 → 09:30
        assertEquals(840, TimeUtils.slotIndexToStartMinute(10));  // 슬롯 10 → 14:00
    }

    @Test
    public void testSlotIndexToEndMinute() {
        assertEquals(570, TimeUtils.slotIndexToEndMinute(0));     // 슬롯 0 → 09:30
        assertEquals(600, TimeUtils.slotIndexToEndMinute(1));     // 슬롯 1 → 10:00
    }

    @Test
    public void testGetSlotTimeRange() {
        assertEquals("09:00 ~ 09:30", TimeUtils.getSlotTimeRange(0));
        assertEquals("09:30 ~ 10:00", TimeUtils.getSlotTimeRange(1));
        assertEquals("10:00 ~ 10:30", TimeUtils.getSlotTimeRange(2));
        assertEquals("14:00 ~ 14:30", TimeUtils.getSlotTimeRange(10));
        assertEquals("17:30 ~ 18:00", TimeUtils.getSlotTimeRange(17));
    }

    @Test
    public void testGetSlotHourLabel() {
        assertEquals("9", TimeUtils.getSlotHourLabel(0));   // 09:00 정시
        assertEquals("", TimeUtils.getSlotHourLabel(1));    // 09:30 반시
        assertEquals("10", TimeUtils.getSlotHourLabel(2));  // 10:00 정시
        assertEquals("", TimeUtils.getSlotHourLabel(3));    // 10:30 반시
        assertEquals("14", TimeUtils.getSlotHourLabel(10)); // 14:00 정시
    }

    // ── 겹침 계산 ────────────────────────────────────

    @Test
    public void testOverlapMinutes() {
        assertEquals(30, TimeUtils.overlapMinutes(540, 630, 600, 720));
        assertEquals(0, TimeUtils.overlapMinutes(540, 600, 600, 660));
        assertEquals(60, TimeUtils.overlapMinutes(540, 720, 600, 660));
        assertEquals(120, TimeUtils.overlapMinutes(600, 720, 600, 720));
    }

    // ── 유효 겹침 필터 ──────────────────────────────

    @Test
    public void testValidMinOverlap() {
        assertFalse(TimeUtils.isValidMinOverlap(0));
        assertFalse(TimeUtils.isValidMinOverlap(30));
        assertTrue(TimeUtils.isValidMinOverlap(60));
        assertTrue(TimeUtils.isValidMinOverlap(90));
        assertTrue(TimeUtils.isValidMinOverlap(120));
        assertTrue(TimeUtils.isValidMinOverlap(150));
        assertTrue(TimeUtils.isValidMinOverlap(180));
        assertFalse(TimeUtils.isValidMinOverlap(210));
    }

    // ── 요일 ────────────────────────────────────────

    @Test
    public void testDayOfWeekToLabel() {
        assertEquals("월", TimeUtils.dayOfWeekToLabel(1));
        assertEquals("금", TimeUtils.dayOfWeekToLabel(5));
        assertEquals("일", TimeUtils.dayOfWeekToLabel(7));
        assertEquals("?", TimeUtils.dayOfWeekToLabel(0));
    }

    @Test
    public void testDayOfWeekToFullLabel() {
        assertEquals("월요일", TimeUtils.dayOfWeekToFullLabel(1));
        assertEquals("금요일", TimeUtils.dayOfWeekToFullLabel(5));
    }

    @Test
    public void testParseDayOfWeek() {
        assertEquals(1, TimeUtils.parseDayOfWeek("월요일"));
        assertEquals(3, TimeUtils.parseDayOfWeek("수"));
        assertEquals(-1, TimeUtils.parseDayOfWeek(""));
    }
}
