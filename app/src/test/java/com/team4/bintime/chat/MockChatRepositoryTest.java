package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void resetDemoState_restoresRooms() {
        repository.cycleDemoState();
        repository.cycleDemoState();
        repository.resetDemoState();
        assertEquals(4, freshRooms().size());
    }
}
