package com.team4.bintime.chat;

import android.os.Handler;
import android.os.Looper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BT-009·BT-010 UI 시연 전용. 메모리 데이터만 바꾸며 실제 인증·권한·RTDB·매칭 종료 서버 함수를 대신하지 않는다.
 * BT-021·BT-022 에서 같은 {@link ChatRepository} 의 RTDB 구현으로 교체한다.
 * 이름과 메시지는 Figma 시안 문구이고, 추천 장소는 AI 결과가 아니다.
 */
public final class MockChatRepository implements ChatRepository {

    public enum DemoState { NORMAL, EMPTY, ERROR }

    interface Scheduler {
        void schedule(Runnable task, long delayMillis);
    }

    static final String MY_UID = "me";
    static final long LOAD_DELAY_MILLIS = 300;
    static final long REPLY_DELAY_MILLIS = 1500;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long MINUTE = 60_000L;
    private static final String[] MOCK_REPLIES = {"좋아요!", "네 확인했어요 :)", "곧 도착해요!"};

    private static MockChatRepository shared;

    public static MockChatRepository shared() {
        if (shared == null) {
            Handler handler = new Handler(Looper.getMainLooper());
            shared = new MockChatRepository(handler::postDelayed, Clock.system(SEOUL));
        }
        return shared;
    }

    private static final class Subscription<T> {
        final String matchId;
        final Listener<T> listener;

        Subscription(String matchId, Listener<T> listener) {
            this.matchId = matchId;
            this.listener = listener;
        }
    }

    private final Scheduler scheduler;
    private final Clock clock;
    private final Map<String, ChatRoom> rooms = new LinkedHashMap<>();
    private final Map<String, List<ChatMessage>> messages = new HashMap<>();
    /** 내가 나간 방. 메시지는 지우지 않고 내 목록과 방 구독에서만 뺀다. */
    private final Set<String> leftRooms = new HashSet<>();
    private final List<Subscription<List<ChatRoom>>> roomListSubscriptions = new ArrayList<>();
    private final List<Subscription<ChatRoom>> roomSubscriptions = new ArrayList<>();
    private final List<Subscription<List<ChatMessage>>> messageSubscriptions = new ArrayList<>();
    private DemoState demoState = DemoState.NORMAL;
    private int nextMessageId = 1;
    private int nextReply;

    MockChatRepository(Scheduler scheduler, Clock clock) {
        this.scheduler = scheduler;
        this.clock = clock;
        seed();
    }

    @Override
    public String currentUid() {
        return MY_UID;
    }

    @Override
    public Registration observeRooms(Listener<List<ChatRoom>> listener) {
        return subscribe(roomListSubscriptions, new Subscription<>(null, listener), this::deliverRooms);
    }

    @Override
    public Registration observeRoom(String matchId, Listener<ChatRoom> listener) {
        return subscribe(roomSubscriptions, new Subscription<>(matchId, listener), this::deliverRoom);
    }

    @Override
    public Registration observeMessages(String matchId, Listener<List<ChatMessage>> listener) {
        return subscribe(messageSubscriptions, new Subscription<>(matchId, listener), this::deliverMessages);
    }

    private interface Delivery<T> {
        void deliver(Subscription<T> subscription);
    }

    /** 첫 전달만 지연해 로딩 상태를 보여주고, 해제된 구독에는 전달하지 않는다. */
    private <T> Registration subscribe(List<Subscription<T>> subscriptions, Subscription<T> subscription,
                                       Delivery<T> delivery) {
        subscriptions.add(subscription);
        scheduler.schedule(() -> {
            if (subscriptions.contains(subscription)) delivery.deliver(subscription);
        }, LOAD_DELAY_MILLIS);
        return () -> subscriptions.remove(subscription);
    }

    @Override
    public void sendMessage(String matchId, String content, SendCallback callback) {
        ChatRoom room = rooms.get(matchId);
        String text = content == null ? "" : content.trim();
        if (room == null || text.isEmpty() || demoState == DemoState.ERROR) {
            callback.onError();
            return;
        }
        append(room, MY_UID, text, room.unreadCount);
        callback.onSuccess();
        scheduler.schedule(() -> receiveMockReply(matchId), REPLY_DELAY_MILLIS);
    }

    @Override
    public void markRead(String matchId) {
        ChatRoom room = rooms.get(matchId);
        if (room == null || room.unreadCount == 0) return;
        rooms.put(matchId, room.withUnread(0));
        notifyRoomChanged(matchId);
    }

    @Override
    public void endMatch(String matchId, ActionCallback callback) {
        ChatRoom room = rooms.get(matchId);
        if (room == null || leftRooms.contains(matchId) || demoState == DemoState.ERROR) {
            callback.onError();
            return;
        }
        if (room.status == ChatRoom.Status.ACTIVE) {
            rooms.put(matchId, room.withStatus(ChatRoom.Status.ENDED));
            notifyRoomChanged(matchId);
        }
        callback.onSuccess();
    }

    @Override
    public void leaveRoom(String matchId, ActionCallback callback) {
        ChatRoom room = rooms.get(matchId);
        if (room == null || room.status != ChatRoom.Status.ENDED || demoState == DemoState.ERROR) {
            callback.onError();
            return;
        }
        // 상대 기록은 남기고 내 목록에서만 숨긴다.
        leftRooms.add(matchId);
        notifyRoomChanged(matchId);
        callback.onSuccess();
    }

    DemoState demoState() {
        return demoState;
    }

    /** 제목을 길게 눌러 정상 → 빈 상태 → 오류 화면을 시연한다. */
    public DemoState cycleDemoState() {
        DemoState[] states = DemoState.values();
        demoState = states[(demoState.ordinal() + 1) % states.length];
        return demoState;
    }

    public void resetDemoState() {
        demoState = DemoState.NORMAL;
    }

    private void receiveMockReply(String matchId) {
        ChatRoom room = rooms.get(matchId);
        if (room == null) return;
        String reply = MOCK_REPLIES[nextReply++ % MOCK_REPLIES.length];
        append(room, room.otherUid, reply, room.unreadCount + 1);
    }

    private void append(ChatRoom room, String senderId, String text, int unread) {
        long now = clock.millis();
        messages.get(room.matchId).add(new ChatMessage("mock-" + nextMessageId++, senderId, text, now));
        rooms.put(room.matchId, room.withLastMessage(text, now, unread));
        notifyRoomChanged(room.matchId);
        for (Subscription<List<ChatMessage>> s : new ArrayList<>(messageSubscriptions)) {
            if (room.matchId.equals(s.matchId)) deliverMessages(s);
        }
    }

    private void notifyRoomChanged(String matchId) {
        for (Subscription<List<ChatRoom>> s : new ArrayList<>(roomListSubscriptions)) deliverRooms(s);
        for (Subscription<ChatRoom> s : new ArrayList<>(roomSubscriptions)) {
            if (matchId.equals(s.matchId)) deliverRoom(s);
        }
    }

    private void deliverRooms(Subscription<List<ChatRoom>> subscription) {
        if (demoState == DemoState.ERROR) {
            subscription.listener.onError();
            return;
        }
        if (demoState == DemoState.EMPTY) {
            subscription.listener.onData(Collections.emptyList());
            return;
        }
        List<ChatRoom> sorted = new ArrayList<>();
        for (ChatRoom room : rooms.values()) {
            if (!leftRooms.contains(room.matchId)) sorted.add(room);
        }
        sorted.sort(Comparator.comparingLong((ChatRoom room) -> room.lastMessageAt).reversed());
        subscription.listener.onData(sorted);
    }

    private void deliverRoom(Subscription<ChatRoom> subscription) {
        ChatRoom room = rooms.get(subscription.matchId);
        if (room == null || leftRooms.contains(subscription.matchId)) subscription.listener.onError();
        else subscription.listener.onData(room);
    }

    private void deliverMessages(Subscription<List<ChatMessage>> subscription) {
        List<ChatMessage> list = messages.get(subscription.matchId);
        if (list == null) subscription.listener.onError();
        else subscription.listener.onData(new ArrayList<>(list));
    }

    private void seed() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(SEOUL);
        int minute = now.getHour() * 60 + now.getMinute();
        String today = now.toLocalDate().toString();
        long nowMillis = clock.millis();

        // 실행 시각과 무관하게 남은 시간이 보이도록 ACTIVE 방은 현재 시각 기준으로 만든다.
        addRoom("match-jimin", "u1", "김지민", "컴퓨터공학과 3학년 · 여성", true, ChatRoom.Status.ACTIVE, today,
                Math.max(0, minute - 18), Math.min(1440, minute + 72), 12,
                "복지관 지하1층 카페 / 북악도서관",
                incoming("안녕하세요!\n어디서 만날까요?", nowMillis - 30 * MINUTE),
                outgoing("도서관 2층 어때요?", nowMillis - 30 * MINUTE),
                incoming("좋아요! 그럼 거기서 봐요 :)", nowMillis - 27 * MINUTE),
                incoming("오늘도 좋은 하루!", nowMillis - 5 * MINUTE));
        addRoom("match-junho", "mock-junho", "준호", "경영학부 2학년 · 남성", false, ChatRoom.Status.ACTIVE, today,
                Math.max(0, minute - 30), Math.min(1440, minute + 60), 3, null,
                outgoing("오늘 공강에 같이 공부할까요?", nowMillis - 50 * MINUTE),
                incoming("네 좋아요!", nowMillis - 45 * MINUTE));

        // 종료 매칭은 지난 시간 스냅샷을 유지한다. 지난 시각은 자정 전후에도 어긋나지 않게 날짜를 함께 계산한다.
        long seoyeonAt = nowMillis - 120 * MINUTE;
        addRoom("match-seoyeon", "mock-seoyeon", "서연", "시각디자인학과 4학년 · 여성", false, ChatRoom.Status.ENDED,
                dateOf(seoyeonAt), 600, 690, 0, null,
                outgoing("수업 끝나고 봐서 반가웠어요!", seoyeonAt - 10 * MINUTE),
                incoming("이번 주에 시간 괜찮아요?", seoyeonAt));
        long jieunAt = nowMillis - 24 * 60 * MINUTE;
        addRoom("match-jieun", "mock-jieun", "지은", "소프트웨어학부 2학년 · 여성", true, ChatRoom.Status.ENDED,
                dateOf(jieunAt), 840, 930, 0, null,
                outgoing("스터디 어떠세요?", jieunAt - 10 * MINUTE),
                incoming("저도 스터디 좋아해요 :)", jieunAt));
    }

    private ChatMessage incoming(String text, long at) {
        return new ChatMessage("mock-" + nextMessageId++, null, text, at);
    }

    private ChatMessage outgoing(String text, long at) {
        return new ChatMessage("mock-" + nextMessageId++, MY_UID, text, at);
    }

    private String dateOf(long millis) {
        return Instant.ofEpochMilli(millis).atZone(SEOUL).toLocalDate().toString();
    }

    private void addRoom(String matchId, String otherUid, String otherName, String otherInfo, boolean hasPhoto,
                         ChatRoom.Status status, String meetingDate, int start, int end, int unread,
                         String place, ChatMessage... seedMessages) {
        List<ChatMessage> list = new ArrayList<>();
        for (ChatMessage m : seedMessages) {
            list.add(m.senderId == null ? new ChatMessage(m.messageId, otherUid, m.content, m.createdAt) : m);
        }
        ChatMessage last = list.get(list.size() - 1);
        messages.put(matchId, list);
        rooms.put(matchId, new ChatRoom(matchId, Arrays.asList(MY_UID, otherUid), status,
                meetingDate, start, end, true, otherUid, otherName, otherInfo, hasPhoto,
                last.content, last.createdAt, unread, place));
    }
}
