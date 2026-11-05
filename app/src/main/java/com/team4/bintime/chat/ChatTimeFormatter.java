package com.team4.bintime.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 채팅 화면의 시간 표시. 시간대는 Asia/Seoul 로 통일한다. */
final class ChatTimeFormatter {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREA);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd", Locale.KOREA);
    private static final DateTimeFormatter DATE_DIVIDER = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREA);

    private ChatTimeFormatter() {
    }

    /** 예: 오늘 14:00 ~ 15:30 */
    static String timeRange(ChatRoom room, long nowMillis, String today, String yesterday) {
        LocalDate date = LocalDate.parse(room.meetingDate);
        LocalDate now = dateOf(nowMillis);
        String day = date.equals(now) ? today : date.equals(now.minusDays(1)) ? yesterday : date.format(SHORT_DATE);
        return day + " " + clock(room.startMinute) + " ~ " + clock(room.endMinute);
    }

    /** 예: 1:12:36. 종료 시각이 지나면 0:00:00 이다. */
    static String remaining(ChatRoom room, long nowMillis) {
        long end = LocalDate.parse(room.meetingDate).atStartOfDay(SEOUL)
                .plusMinutes(room.endMinute).toInstant().toEpochMilli();
        long seconds = Math.max(0, (end - nowMillis) / 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /** 예: 오후 2:05 */
    static String messageTime(long millis) {
        return at(millis).format(MESSAGE_TIME);
    }

    /** 오늘은 시각, 어제는 어제, 그 전은 날짜로 표시한다. */
    static String listTime(long millis, long nowMillis, String yesterday) {
        LocalDate date = dateOf(millis);
        LocalDate now = dateOf(nowMillis);
        if (date.equals(now)) return messageTime(millis);
        return date.equals(now.minusDays(1)) ? yesterday : date.format(SHORT_DATE);
    }

    /** 예: 2026년 10월 7일 수요일 */
    static String dateDivider(long millis) {
        return at(millis).format(DATE_DIVIDER);
    }

    static boolean sameDay(long a, long b) {
        return dateOf(a).equals(dateOf(b));
    }

    private static String clock(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private static ZonedDateTime at(long millis) {
        return Instant.ofEpochMilli(millis).atZone(SEOUL);
    }

    private static LocalDate dateOf(long millis) {
        return at(millis).toLocalDate();
    }
}
