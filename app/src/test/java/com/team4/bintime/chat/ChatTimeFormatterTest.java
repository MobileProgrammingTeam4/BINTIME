package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;

public class ChatTimeFormatterTest {

    private static long kst(String isoWithOffset) {
        return Instant.parse(isoWithOffset).toEpochMilli();
    }

    /** 2026-10-07 14:17:24 KST */
    private static final long NOW = kst("2026-10-07T05:17:24Z");

    private static ChatRoom room(String meetingDate, int start, int end) {
        return new ChatRoom("match", Arrays.asList("me", "other"), ChatRoom.Status.ACTIVE, meetingDate,
                start, end, true, "other", "김지민", null, true, "", 0, 0, null);
    }

    @Test
    public void timeRange_labelsTodayYesterdayAndOtherDates() {
        assertEquals("오늘 14:00 ~ 15:30",
                ChatTimeFormatter.timeRange(room("2026-10-07", 840, 930), NOW, "오늘", "어제"));
        assertEquals("어제 14:00 ~ 15:30",
                ChatTimeFormatter.timeRange(room("2026-10-06", 840, 930), NOW, "오늘", "어제"));
        assertEquals("10/05 09:05 ~ 24:00",
                ChatTimeFormatter.timeRange(room("2026-10-05", 545, 1440), NOW, "오늘", "어제"));
    }

    @Test
    public void remaining_matchesFigmaFormat_andStopsAtZero() {
        assertEquals("1:12:36", ChatTimeFormatter.remaining(room("2026-10-07", 840, 930), NOW));
        assertEquals("0:00:00", ChatTimeFormatter.remaining(room("2026-10-07", 600, 690), NOW));
        assertEquals("0:00:00", ChatTimeFormatter.remaining(room("2026-10-06", 840, 930), NOW));
    }

    @Test
    public void messageTime_usesKoreanMeridiemInSeoulTime() {
        assertEquals("오후 2:05", ChatTimeFormatter.messageTime(kst("2026-10-07T05:05:00Z")));
        assertEquals("오전 9:00", ChatTimeFormatter.messageTime(kst("2026-10-07T00:00:00Z")));
    }

    @Test
    public void listTime_showsTimeYesterdayOrDate() {
        assertEquals("오후 2:05", ChatTimeFormatter.listTime(kst("2026-10-07T05:05:00Z"), NOW, "어제"));
        assertEquals("어제", ChatTimeFormatter.listTime(kst("2026-10-06T05:05:00Z"), NOW, "어제"));
        assertEquals("10/05", ChatTimeFormatter.listTime(kst("2026-10-05T05:05:00Z"), NOW, "어제"));
    }

    @Test
    public void listTime_roomWithoutMessages_isBlank() {
        // 서버가 막 만든 방은 lastMessageAt 이 0 이다. 1970-01-01("01/01")로 보이면 안 된다.
        assertEquals("", ChatTimeFormatter.listTime(0L, NOW, "어제"));
    }

    @Test
    public void dateDivider_matchesFigmaFormat() {
        assertEquals("2026년 10월 7일 수요일", ChatTimeFormatter.dateDivider(NOW));
    }

    @Test
    public void sameDay_splitsAtSeoulMidnight() {
        long beforeMidnight = kst("2026-10-07T14:59:00Z");
        long afterMidnight = kst("2026-10-07T15:00:00Z");
        assertFalse(ChatTimeFormatter.sameDay(beforeMidnight, afterMidnight));
        assertTrue(ChatTimeFormatter.sameDay(afterMidnight, afterMidnight + 60_000));
    }
}
