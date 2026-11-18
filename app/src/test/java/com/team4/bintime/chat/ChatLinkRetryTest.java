package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 채팅방 준비 재시도 순서·간격·늦은 콜백 (BT-034 §3). */
public class ChatLinkRetryTest {

    private static final String MATCH = hex('a'), BLOCKING = hex('b');

    private static String hex(char c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append(c);
        return sb.toString();
    }

    /** 응답을 테스트가 직접 보낸다. */
    private static final class FakeRepository implements ChatLinkRepository {
        final List<String> calls = new ArrayList<>();
        final List<Callback> pending = new ArrayList<>();
        int cancels;

        @Override
        public Request ensure(String matchId, Callback callback) {
            calls.add(matchId);
            pending.add(callback);
            return () -> cancels++;
        }

        Callback last() {
            return pending.get(pending.size() - 1);
        }
    }

    private final FakeRepository repo = new FakeRepository();
    private long now = 1_000_000;
    private final ChatLinkRetry retry = new ChatLinkRetry(repo, () -> now, MATCH);
    private final List<String> events = new ArrayList<>();
    private long lastRetryAt = -1;
    private ChatLinkFailure lastFailure;
    private final ChatLinkRetry.Listener listener = new ChatLinkRetry.Listener() {
        @Override public void onPreparing() { events.add("preparing"); }
        @Override public void onReady(String chatRoomId) { events.add("ready:" + chatRoomId); }
        @Override public void onUnavailable() { events.add("unavailable"); }
        @Override public void onFailed(ChatLinkFailure failure, long retryAtMillis) {
            events.add("failed:" + failure.kind);
            lastFailure = failure;
            lastRetryAt = retryAtMillis;
        }
    };

    private static Map<String, Object> details(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    @Test
    public void ready_reportsRoomAndClearsInFlight() {
        assertTrue(retry.start(listener));
        assertTrue(retry.inFlight());
        repo.last().onResult(MATCH, true);
        assertEquals(List.of("preparing", "ready:" + MATCH), events);
        assertFalse(retry.inFlight());
    }

    @Test
    public void duplicateClickWhileInFlight_isIgnored() {
        retry.start(listener);
        assertFalse(retry.start(listener));
        assertEquals(1, repo.calls.size());
    }

    @Test
    public void synchronousCallback_doesNotStayInFlight() {
        // 반환 전에 콜백이 오는 저장소(Mock·로그인 없음 경로). 예전에는 끝난 요청이 current 에 남아 다시 시도할 수 없었다.
        ChatLinkRetry sync = new ChatLinkRetry((id, cb) -> {
            cb.onFailure(ChatLinkFailure.from("UNAVAILABLE", null));
            return () -> {};
        }, () -> now, MATCH);
        assertTrue(sync.start(listener));
        assertFalse(sync.inFlight());
        now += ChatLinkFailure.DEFAULT_RETRY_MILLIS;
        assertTrue(sync.start(listener));
    }

    @Test
    public void synchronousReady_mockRepository() {
        ChatLinkRetry sync = new ChatLinkRetry(ChatLinkRepositories.MOCK, () -> now, MATCH);
        assertTrue(sync.start(listener));
        assertFalse(sync.inFlight());
        assertEquals("ready:" + MATCH, events.get(1));
        assertTrue(sync.start(listener)); // 다시 눌러도 막히지 않는다
    }

    @Test
    public void retryLater_blocksUntilServerTime() {
        retry.start(listener);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_RETRY_LATER", "retryAfterMillis", 7_000)));
        assertEquals(now + 7_000, lastRetryAt);
        now += 6_999;
        assertFalse(retry.start(listener));
        now += 1;
        assertTrue(retry.start(listener));
        assertEquals(2, repo.calls.size());
    }

    @Test
    public void linkBusy_usesRetryAfter_andClampsHugeValues() {
        retry.start(listener);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_LINK_BUSY", "retryAfterMillis", 10_000_000L)));
        assertEquals(now + ChatLinkFailure.MAX_RETRY_MILLIS, lastRetryAt);
    }

    @Test
    public void pairBusy_preparesBlockingFirstThenOriginalOnce() {
        retry.start(listener);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_PAIR_BUSY", "blockingMatchId", BLOCKING)));
        assertEquals(List.of(MATCH, BLOCKING), repo.calls);
        assertTrue(retry.inFlight());
        repo.last().onResult(BLOCKING, true);
        assertEquals(List.of(MATCH, BLOCKING, MATCH), repo.calls);
        repo.last().onResult(BLOCKING, true); // 재사용 방: 원래 매칭도 같은 방
        assertEquals("ready:" + BLOCKING, events.get(events.size() - 1));
        assertFalse(retry.inFlight());
    }

    @Test
    public void pairBusyAgain_stopsChainAndWaits() {
        retry.start(listener);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_PAIR_BUSY", "blockingMatchId", BLOCKING)));
        repo.last().onResult(BLOCKING, true);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_PAIR_BUSY", "blockingMatchId", BLOCKING)));
        assertEquals(3, repo.calls.size()); // 더 따라가지 않는다
        assertEquals(ChatLinkFailure.Kind.RETRY_LATER, lastFailure.kind);
        assertFalse(retry.inFlight());
    }

    @Test
    public void blockingMatchFails_reportsFailureWithoutCallingOriginal() {
        retry.start(listener);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_PAIR_BUSY", "blockingMatchId", BLOCKING)));
        repo.last().onFailure(ChatLinkFailure.from("UNAVAILABLE", null));
        assertEquals(2, repo.calls.size());
        assertEquals(ChatLinkFailure.Kind.RETRY_LATER, lastFailure.kind);
    }

    @Test
    public void permanent_stopsFurtherRetries() {
        retry.start(listener);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION", details("reason", "INVALID_ROOM")));
        assertEquals(ChatLinkFailure.Kind.PERMANENT, lastFailure.kind);
        assertEquals(0, lastRetryAt);
        now += 1_000_000;
        assertFalse(retry.start(listener));
        assertTrue(retry.stopped());
    }

    @Test
    public void notReady_reportsUnavailable() {
        retry.start(listener);
        repo.last().onResult(MATCH, false);
        assertEquals("unavailable", events.get(events.size() - 1));
    }

    @Test
    public void lateCallbackAfterCancel_isIgnored() {
        retry.start(listener);
        ChatLinkRepository.Callback late = repo.last();
        retry.cancel();
        assertEquals(1, repo.cancels);
        late.onResult(MATCH, true);
        assertEquals(List.of("preparing"), events);
        assertFalse(retry.start(listener));
    }

    @Test
    public void duplicateCallbackFromSameCall_isIgnored() {
        retry.start(listener);
        ChatLinkRepository.Callback cb = repo.last();
        cb.onFailure(ChatLinkFailure.from("UNAVAILABLE", null));
        cb.onResult(MATCH, true);
        assertEquals(List.of("preparing", "failed:RETRY_LATER"), events);
    }

    // ---- 실패 분류 ----

    @Test
    public void failure_classification() {
        assertEquals(ChatLinkFailure.Kind.UNAUTHENTICATED, ChatLinkFailure.from("UNAUTHENTICATED", null).kind);
        assertEquals(ChatLinkFailure.Kind.PERMANENT, ChatLinkFailure.from("PERMISSION_DENIED", null).kind);
        assertEquals(ChatLinkFailure.Kind.PERMANENT, ChatLinkFailure.from("NOT_FOUND", null).kind);
        assertEquals(ChatLinkFailure.Kind.RETRY_LATER, ChatLinkFailure.from("DEADLINE_EXCEEDED", null).kind);
        assertEquals(ChatLinkFailure.Kind.RETRY_LATER, ChatLinkFailure.from(null, null).kind);
        for (String reason : new String[]{"INVALID_MATCH", "INVALID_ROOM", "ROOM_ARCHIVED", "ROOM_MEMBERS_CHANGED", "ROOM_ENTRY_CHANGED"}) {
            assertEquals(reason, ChatLinkFailure.Kind.PERMANENT,
                    ChatLinkFailure.from("FAILED_PRECONDITION", details("reason", reason)).kind);
        }
        ChatLinkFailure stale = ChatLinkFailure.from("FAILED_PRECONDITION", details("reason", "STALE_LINK"));
        assertEquals(ChatLinkFailure.Kind.RETRY_LATER, stale.kind);
        assertEquals(ChatLinkFailure.DEFAULT_RETRY_MILLIS, stale.retryAfterMillis);
        assertEquals(ChatLinkFailure.Kind.RETRY_LATER, ChatLinkFailure.from("FAILED_PRECONDITION", null).kind);
    }

    @Test
    public void pairBusy_withInvalidId_fallsBackToRetryLater() {
        ChatLinkFailure f = ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_PAIR_BUSY", "blockingMatchId", "../other"));
        assertEquals(ChatLinkFailure.Kind.RETRY_LATER, f.kind);
        assertNull(f.blockingMatchId);
    }

    @Test
    public void retryAfter_tooSmallIsRaisedToMinimum() {
        ChatLinkFailure f = ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_LINK_BUSY", "retryAfterMillis", -5));
        assertEquals(ChatLinkFailure.MIN_RETRY_MILLIS, f.retryAfterMillis);
    }
}
