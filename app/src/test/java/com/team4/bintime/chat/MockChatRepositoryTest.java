package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class MockChatRepositoryTest {

    /** 2026-10-07 14:18 KST */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T05:18:00Z"), ZoneId.of("Asia/Seoul"));

    private static final class ManualScheduler implements MockChatRepository.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMillis) {
            tasks.add(task);
        }

        void runAll() {
            List<Runnable> current = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : current) task.run();
        }
    }

    private static final class Capture<T> implements ChatRepository.Listener<T> {
        T last;
        int errors;

        @Override
        public void onData(T data) {
            last = data;
        }

        @Override
        public void onError() {
            errors++;
        }
    }

    private static final class SendResult implements ChatRepository.SendCallback {
        int successes, errors;

        @Override
        public void onSuccess() {
            successes++;
        }

        @Override
        public void onError() {
            errors++;
        }
    }

    private ManualScheduler scheduler;
    private MockChatRepository repository;
    private Capture<List<ChatRoom>> liveRooms;

    @Before
    public void setUp() {
        scheduler = new ManualScheduler();
        repository = new MockChatRepository(scheduler, CLOCK);
        liveRooms = new Capture<>();
        repository.observeRooms(liveRooms);
        scheduler.runAll();
    }

    /** 대기 중인 답장을 실행하지 않도록 새 구독과 스케줄러 실행은 데모 상태 확인에만 쓴다. */
    private List<ChatRoom> freshRooms() {
        Capture<List<ChatRoom>> capture = new Capture<>();
        repository.observeRooms(capture);
        scheduler.runAll();
        return capture.last;
    }

    private ChatRoom room(String matchId) {
        for (ChatRoom room : liveRooms.last) {
            if (room.matchId.equals(matchId)) return room;
        }
        return null;
    }

    @Test
    public void newlyConnectedRoomWithoutMessages_isListedFirstByLinkedAt() {
        com.team4.bintime.request.RequestRepository.Item item = new com.team4.bintime.request.RequestRepository.Item(
                "new-match", "mock-me", "uid-new", "mock-me", "스터디", "2026-10-07", 840, 930,
                com.team4.bintime.request.RequestRepository.Status.ACCEPTED,
                com.team4.bintime.request.RequestRepository.MatchStatus.ACTIVE, 0);
        repository.connectMatchForDemo(item);
        repository.connectMatchForDemo(item); // 다시 연결해도 방·연결 시각이 늘거나 바뀌지 않는다
        List<ChatRoom> rooms = freshRooms();
        assertEquals("new-match", rooms.get(0).matchId);
        assertEquals(0L, rooms.get(0).lastMessageAt);
        assertEquals(5, rooms.size());
    }

    @Test
    public void rooms_areSortedByLatestMessage_andFilterByStatus() {
        List<ChatRoom> rooms = liveRooms.last;
        assertEquals(4, rooms.size());
        assertEquals("김지민", rooms.get(0).otherName);
        assertEquals("지은", rooms.get(3).otherName);
        assertEquals(4, count(rooms, ChatRoom.Filter.ALL));
        assertEquals(2, count(rooms, ChatRoom.Filter.MATCHED));
        assertEquals(2, count(rooms, ChatRoom.Filter.ENDED));
    }

    private static int count(List<ChatRoom> rooms, ChatRoom.Filter filter) {
        int count = 0;
        for (ChatRoom room : rooms) {
            if (filter.matches(room)) count++;
        }
        return count;
    }

    @Test
    public void seed_previewMatchesLastMessage_andActiveRoomUsesCurrentTime() {
        ChatRoom jimin = room("match-jimin");
        Capture<List<ChatMessage>> messages = new Capture<>();
        repository.observeMessages("match-jimin", messages);
        scheduler.runAll();
        assertEquals(messages.last.get(messages.last.size() - 1).content, jimin.lastMessage);
        assertEquals("2026-10-07", jimin.meetingDate);
        assertEquals(840, jimin.startMinute);
        assertEquals(930, jimin.endMinute);
    }

    @Test
    public void send_addsMessageAndUpdatesPreview_trimmingContent() {
        Capture<List<ChatMessage>> messages = new Capture<>();
        repository.observeMessages("match-junho", messages);
        scheduler.runAll();
        int before = messages.last.size();
        SendResult result = new SendResult();

        repository.sendMessage("match-junho", "  도서관 앞 어때요?  ", result);

        assertEquals(1, result.successes);
        assertEquals(before + 1, messages.last.size());
        ChatMessage sent = messages.last.get(before);
        assertEquals("도서관 앞 어때요?", sent.content);
        assertEquals(repository.currentUid(), sent.senderId);
        assertEquals("도서관 앞 어때요?", room("match-junho").lastMessage);
    }

    @Test
    public void send_rejectsBlankContentAndUnknownRoom() {
        SendResult result = new SendResult();
        repository.sendMessage("match-junho", "   ", result);
        repository.sendMessage("match-junho", null, result);
        repository.sendMessage("unknown", "안녕하세요", result);
        assertEquals(3, result.errors);
        assertEquals(0, result.successes);
    }

    @Test
    public void endedRoom_keepsMessagingAndStatus() {
        ChatRoom before = room("match-seoyeon");
        SendResult result = new SendResult();

        repository.sendMessage("match-seoyeon", "다음에 또 봐요", result);

        assertEquals(1, result.successes);
        ChatRoom after = room("match-seoyeon");
        assertEquals(ChatRoom.Status.ENDED, after.status);
        assertEquals("다음에 또 봐요", after.lastMessage);
        assertEquals(before.meetingDate, after.meetingDate);
        assertEquals(before.startMinute, after.startMinute);
        assertEquals(before.endMinute, after.endMinute);
    }

    @Test
    public void mockReply_arrivesLaterAndIncreasesUnread_untilMarkedRead() {
        int unread = room("match-junho").unreadCount;
        repository.sendMessage("match-junho", "좋아요", new SendResult());
        assertEquals(unread, room("match-junho").unreadCount);

        scheduler.runAll();
        ChatRoom replied = room("match-junho");
        assertEquals(unread + 1, replied.unreadCount);
        assertEquals("좋아요!", replied.lastMessage);

        repository.markRead("match-junho");
        assertEquals(0, room("match-junho").unreadCount);
    }

    @Test
    public void removedRegistration_receivesNoData() {
        Capture<List<ChatRoom>> capture = new Capture<>();
        ChatRepository.Registration registration = repository.observeRooms(capture);
        registration.remove();
        scheduler.runAll();
        repository.markRead("match-jimin");
        assertNull(capture.last);
    }

    @Test
    public void unknownRoom_reportsError() {
        Capture<ChatRoom> room = new Capture<>();
        Capture<List<ChatMessage>> messages = new Capture<>();
        repository.observeRoom("unknown", room);
        repository.observeMessages("unknown", messages);
        scheduler.runAll();
        assertEquals(1, room.errors);
        assertEquals(1, messages.errors);
    }

    @Test
    public void demoStates_cycleThroughEmptyErrorAndBack() {
        assertEquals(MockChatRepository.DemoState.EMPTY, repository.cycleDemoState());
        assertTrue(freshRooms().isEmpty());

        assertEquals(MockChatRepository.DemoState.ERROR, repository.cycleDemoState());
        Capture<List<ChatRoom>> capture = new Capture<>();
        repository.observeRooms(capture);
        scheduler.runAll();
        assertEquals(1, capture.errors);
        assertNull(capture.last);
        SendResult result = new SendResult();
        repository.sendMessage("match-jimin", "안녕하세요", result);
        assertEquals(1, result.errors);

        assertEquals(MockChatRepository.DemoState.NORMAL, repository.cycleDemoState());
        assertNotNull(freshRooms());
        assertEquals(4, freshRooms().size());
    }

    private static final class ActionResult implements ChatRepository.ActionCallback, ChatRepository.LeaveCallback {
        int successes, errors;
        ChatLeaveFailure lastFailure;

        @Override
        public void onSuccess() {
            successes++;
        }

        @Override
        public void onError() {
            errors++;
        }

        @Override
        public void onError(ChatLeaveFailure failure) {
            lastFailure = failure;
            errors++;
        }
    }

    @Test
    public void endMatch_changesActiveToEnded_keepingSnapshotAndMessaging() {
        ChatRoom before = room("match-jimin");
        ActionResult result = new ActionResult();

        repository.endMatch("match-jimin", result);

        assertEquals(1, result.successes);
        ChatRoom after = room("match-jimin");
        assertEquals(ChatRoom.Status.ENDED, after.status);
        assertEquals(before.meetingDate, after.meetingDate);
        assertEquals(before.startMinute, after.startMinute);
        assertEquals(before.endMinute, after.endMinute);
        assertEquals(3, count(liveRooms.last, ChatRoom.Filter.ENDED));

        SendResult send = new SendResult();
        repository.sendMessage("match-jimin", "종료 후에도 보낼 수 있어요", send);
        assertEquals(1, send.successes);
    }

    @Test
    public void endMatch_isIdempotent_andRejectsUnknownRoom() {
        ActionResult result = new ActionResult();
        repository.endMatch("match-seoyeon", result);
        repository.endMatch("unknown", result);
        assertEquals(1, result.successes);
        assertEquals(1, result.errors);
        assertEquals(ChatRoom.Status.ENDED, room("match-seoyeon").status);
    }

    @Test public void selectedFutureEndKeepsOtherAppointmentAndPreventsReviewPermanently() {
        com.team4.bintime.request.MatchAppointment current = new com.team4.bintime.request.MatchAppointment("one", "match-jimin", "2026-10-07", 840, 930,
                "스터디", com.team4.bintime.request.MatchAppointment.Status.ACTIVE, null, false);
        com.team4.bintime.request.MatchAppointment future = new com.team4.bintime.request.MatchAppointment("two", "match-jimin", "2026-10-08", 600, 690,
                "대화", com.team4.bintime.request.MatchAppointment.Status.ACTIVE, null, false);
        repository.setAppointmentsForDemo("match-jimin", java.util.Arrays.asList(current, future), false);
        ActionResult result = new ActionResult(); repository.endMatch("two", result);
        ChatRoom after = room("match-jimin");
        assertEquals(1, result.successes); assertEquals(ChatRoom.Status.ACTIVE, after.status);
        assertEquals(com.team4.bintime.request.MatchAppointment.Status.ACTIVE, after.appointments.get(0).status);
        assertEquals(Boolean.FALSE, after.appointments.get(1).reviewEligible);
        assertEquals(600, after.appointments.get(1).startMinute);
        repository.leaveRoom("match-jimin", result); assertEquals(1, result.errors);
        assertEquals(ChatLeaveFailure.ACTIVE_MATCH, result.lastFailure);
    }

    /** 서버(BT-043)와 같이 종료된 약속이 있어도 연결된 약속 중 하나라도 ACTIVE 면 나갈 수 없다. */
    @Test public void leaveRoom_rejectsWhenAnyAppointmentIsActive() {
        com.team4.bintime.request.MatchAppointment ended = new com.team4.bintime.request.MatchAppointment("one", "match-seoyeon", "2026-10-06", 840, 930,
                "스터디", com.team4.bintime.request.MatchAppointment.Status.ENDED, true, false);
        com.team4.bintime.request.MatchAppointment active = new com.team4.bintime.request.MatchAppointment("two", "match-seoyeon", "2026-10-09", 600, 690,
                "대화", com.team4.bintime.request.MatchAppointment.Status.ACTIVE, null, false);
        repository.setAppointmentsForDemo("match-seoyeon", java.util.Arrays.asList(ended, active), false);
        ActionResult result = new ActionResult();
        repository.leaveRoom("match-seoyeon", result);
        assertEquals(ChatLeaveFailure.ACTIVE_MATCH, result.lastFailure);
        assertEquals(0, result.successes);
    }

    @Test public void archivedConversationKeepsRecordsAndRejectsSending() {
        ChatRoom before = room("match-jimin");
        repository.setAppointmentsForDemo("match-jimin", before.appointments, true);
        SendResult result = new SendResult(); repository.sendMessage("match-jimin", "거부", result);
        assertEquals(1, result.errors); assertEquals(0, result.successes);
        ChatRoom archived = room("match-jimin");
        assertEquals(ChatRoom.Status.ENDED, archived.status); assertFalse(archived.chatReady);
        assertEquals(before.lastMessage, archived.lastMessage);
        assertTrue(ChatRoom.Filter.ENDED.matches(archived));
    }

    @Test
    public void leaveRoom_rejectsActiveRoom() {
        ActionResult result = new ActionResult();
        repository.leaveRoom("match-jimin", result);
        assertEquals(1, result.errors);
        assertEquals(ChatLeaveFailure.ACTIVE_MATCH, result.lastFailure);
        assertEquals(4, liveRooms.last.size());
    }

    @Test
    public void leaveRoom_hidesEndedRoomOnlyFromMyList() {
        Capture<ChatRoom> roomCapture = new Capture<>();
        repository.observeRoom("match-seoyeon", roomCapture);
        scheduler.runAll();
        ActionResult result = new ActionResult();

        repository.leaveRoom("match-seoyeon", result);

        assertEquals(1, result.successes);
        assertEquals(3, liveRooms.last.size());
        for (ChatRoom room : liveRooms.last) assertNotEquals("match-seoyeon", room.matchId);
        assertEquals(1, roomCapture.errors);
        // 상대 기록은 지우지 않는다.
        Capture<List<ChatMessage>> messages = new Capture<>();
        repository.observeMessages("match-seoyeon", messages);
        scheduler.runAll();
        assertFalse(messages.last.isEmpty());
    }

    @Test
    public void leaveRoom_afterManualEnd_isAllowed() {
        ActionResult result = new ActionResult();
        repository.endMatch("match-junho", result);
        repository.leaveRoom("match-junho", result);
        assertEquals(2, result.successes);
        assertEquals(3, liveRooms.last.size());
    }

    @Test
    public void resetDemoState_restoresRooms() {
        repository.cycleDemoState();
        repository.cycleDemoState();
        repository.resetDemoState();
        assertEquals(4, freshRooms().size());
    }
}
