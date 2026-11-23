package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.team4.bintime.request.MatchAppointment;
import com.team4.bintime.request.MatchAppointmentPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** BT-035 채팅 상단 카드의 약속 선택과 경계. 결정 A: 서버 종료 지연 구간은 마지막 약속 카드 + 0:00:00. */
public class ChatAppointmentDisplayTest {

    private TimeZone originalZone;

    @Before
    public void rememberZone() {
        originalZone = TimeZone.getDefault();
    }

    @After
    public void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    /** "2026-10-07T14:17:24" 처럼 KST 벽시계 시각을 받는다. */
    private static long kst(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "+09:00").toInstant().toEpochMilli();
    }

    private static MatchAppointment active(String id, String date, int start, int end) {
        return new MatchAppointment(id, "room", date, start, end, "STUDY", MatchAppointment.Status.ACTIVE, null, false);
    }

    private static MatchAppointment ended(String id, String date, int start, int end) {
        return new MatchAppointment(id, "room", date, start, end, "STUDY", MatchAppointment.Status.ENDED, true, false);
    }

    private static ChatRoom room(List<MatchAppointment> items) {
        return new ChatRoom("room", Arrays.asList("me", "other"), ChatRoom.Status.ACTIVE, "2026-10-07",
                0, 60, true, "other", "지민", null, false, "", 0, 0, null).withAppointments(items, false);
    }

    private static String remaining(List<MatchAppointment> items, long now) {
        MatchAppointment picked = ChatAppointmentDisplay.pick(items, now);
        return ChatTimeFormatter.remaining(room(items).withDisplayAppointment(picked), now);
    }

    private static String range(List<MatchAppointment> items, long now) {
        MatchAppointment picked = ChatAppointmentDisplay.pick(items, now);
        return ChatTimeFormatter.timeRange(room(items).withDisplayAppointment(picked), now, "오늘", "어제");
    }

    // ---- 기존 정책과 같은 구간: MatchAppointmentPolicy.representative 를 그대로 따른다 ----

    @Test
    public void inProgressAndFutureFollowExistingPolicy() {
        MatchAppointment now = active("now", "2026-10-07", 840, 930);
        MatchAppointment later = active("later", "2026-10-07", 1020, 1080);
        List<MatchAppointment> items = Arrays.asList(later, now);
        long at = kst("2026-10-07T14:17:24");
        assertSame(MatchAppointmentPolicy.representative(items, at), ChatAppointmentDisplay.pick(items, at));
        assertSame(now, ChatAppointmentDisplay.pick(items, at));
        // 진행 중이 끝나면 미래 약속(예정)으로 넘어간다. 지난 ACTIVE 가 남아 있어도 미래가 우선이다.
        assertSame(later, ChatAppointmentDisplay.pick(items, kst("2026-10-07T15:30:00")));
    }

    @Test
    public void emptyOrAllEndedHasNoCard() {
        assertNull(ChatAppointmentDisplay.pick(Collections.emptyList(), kst("2026-10-07T14:00:00")));
        List<MatchAppointment> items = Collections.singletonList(ended("a", "2026-10-07", 840, 900));
        assertNull(ChatAppointmentDisplay.pick(items, kst("2026-10-07T16:00:00")));
    }

    // ---- 서버 종료 지연 (결정 A) ----

    @Test
    public void overdueActiveKeepsLastCardWithZeroRemaining() {
        MatchAppointment only = active("a", "2026-10-07", 840, 930);
        List<MatchAppointment> items = Collections.singletonList(only);
        long afterEnd = kst("2026-10-07T15:31:10");
        assertNull("기존 정책은 고를 약속이 없다", MatchAppointmentPolicy.representative(items, afterEnd));
        assertSame(only, ChatAppointmentDisplay.pick(items, afterEnd));
        assertEquals("0:00:00", remaining(items, afterEnd));
        assertEquals("오늘 14:00 ~ 15:30", range(items, afterEnd));
        assertEquals("방은 여전히 ACTIVE(배너 없음)", ChatRoom.Status.ACTIVE, room(items).status);
        assertEquals(0, MatchAppointmentPolicy.additionalCount(items, afterEnd));
    }

    @Test
    public void overdueEndBoundaryIsHalfOpen() {
        List<MatchAppointment> items = Collections.singletonList(active("a", "2026-10-07", 840, 930));
        assertEquals("0:00:01", remaining(items, kst("2026-10-07T15:29:59")));
        // 종료 시각과 같으면 [start, end) 밖이다. 카드는 그대로, 남은 시간은 0.
        assertEquals("0:00:00", remaining(items, kst("2026-10-07T15:30:00")));
    }

    @Test
    public void overdueChoosesLatestEndingActiveAndIgnoresEnded() {
        MatchAppointment early = active("early", "2026-10-07", 600, 660);
        MatchAppointment late = active("late", "2026-10-07", 840, 930);
        // 서버가 이미 ENDED 로 바꾼 약속은 더 늦게 끝나도 지연 카드 후보가 아니다.
        MatchAppointment endedLater = ended("endedLater", "2026-10-07", 960, 1020);
        List<MatchAppointment> items = Arrays.asList(endedLater, early, late);
        assertSame(late, ChatAppointmentDisplay.pick(items, kst("2026-10-07T18:00:00")));
    }

    @Test
    public void overdueTieOnEndIsDeterministic() {
        MatchAppointment b = active("b", "2026-10-07", 840, 930);
        MatchAppointment a = active("a", "2026-10-07", 870, 930);
        long after = kst("2026-10-07T16:00:00");
        assertEquals("b", ChatAppointmentDisplay.pick(Arrays.asList(a, b), after).matchId);
        assertEquals("b", ChatAppointmentDisplay.pick(Arrays.asList(b, a), after).matchId);
    }

    // ---- 경계 (계획 3번) ----

    @Test
    public void adjacentAppointmentsSwitchExactlyAtBoundary() {
        MatchAppointment first = active("first", "2026-10-07", 840, 900);   // [14:00, 15:00)
        MatchAppointment second = active("second", "2026-10-07", 900, 960); // [15:00, 16:00)
        List<MatchAppointment> items = Arrays.asList(second, first);
        assertSame(first, ChatAppointmentDisplay.pick(items, kst("2026-10-07T14:59:59")));
        assertEquals("0:00:01", remaining(items, kst("2026-10-07T14:59:59")));
        assertSame(second, ChatAppointmentDisplay.pick(items, kst("2026-10-07T15:00:00")));
        assertEquals("1:00:00", remaining(items, kst("2026-10-07T15:00:00")));
        // 둘 다 끝났는데 서버가 아직 안 바꾸면 늦게 끝난 쪽 카드를 남긴다.
        assertSame(second, ChatAppointmentDisplay.pick(items, kst("2026-10-07T16:00:00")));
    }

    @Test
    public void clockJumpForwardAndBackRecomputesEachTime() {
        MatchAppointment first = active("first", "2026-10-07", 840, 900);
        MatchAppointment second = active("second", "2026-10-07", 1020, 1080);
        List<MatchAppointment> items = Arrays.asList(first, second);
        long before = kst("2026-10-07T14:30:00");
        long jumped = kst("2026-10-07T17:10:00"); // 장시간 백그라운드·서버 보정으로 한 번에 넘어감
        assertSame(first, ChatAppointmentDisplay.pick(items, before));
        assertSame(second, ChatAppointmentDisplay.pick(items, jumped));
        assertEquals("0:50:00", remaining(items, jumped));
        // 보정 값이 뒤로 와도 저장된 상태 없이 그 시각 기준으로 다시 고른다.
        assertSame(first, ChatAppointmentDisplay.pick(items, before));
        assertEquals(1, MatchAppointmentPolicy.additionalCount(items, before));
    }

    @Test
    public void utcDeviceUsesSeoulMidnightForTodayAndYesterday() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        MatchAppointment midnight = active("m", "2026-10-08", 0, 60); // KST 10/08 00:00~01:00
        List<MatchAppointment> items = Collections.singletonList(midnight);
        long kst0030 = Instant.parse("2026-10-07T15:30:00Z").toEpochMilli(); // UTC 로는 아직 10/07
        assertSame(midnight, ChatAppointmentDisplay.pick(items, kst0030));
        assertEquals("오늘 00:00 ~ 01:00", range(items, kst0030));
        assertEquals("0:30:00", remaining(items, kst0030));

        MatchAppointment lastSlot = active("late", "2026-10-08", 1380, 1440); // KST 10/08 23:00~24:00
        List<MatchAppointment> lateItems = Collections.singletonList(lastSlot);
        long kstNextDay0010 = Instant.parse("2026-10-08T15:10:00Z").toEpochMilli(); // KST 10/09 00:10
        assertSame("자정을 넘긴 종료 지연", lastSlot, ChatAppointmentDisplay.pick(lateItems, kstNextDay0010));
        assertEquals("어제 23:00 ~ 24:00", range(lateItems, kstNextDay0010));
        assertEquals("0:00:00", remaining(lateItems, kstNextDay0010));
    }
}
