package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 성사 화면·완료 카드의 채팅방 준비 진입점 (BT-034 §3, MatchChatNavigator). */
public class ChatLinkLauncherTest {

    private static final String MATCH = hex('a'), BLOCKING = hex('b');

    private static String hex(char c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append(c);
        return sb.toString();
    }

    private static final class FakeRepository implements ChatLinkRepository {
        final List<String> calls = new ArrayList<>();
        final List<Callback> pending = new ArrayList<>();

        @Override
        public Request ensure(String matchId, Callback callback) {
            calls.add(matchId);
            pending.add(callback);
            return () -> {};
        }

        Callback last() {
            return pending.get(pending.size() - 1);
        }
    }

    private final FakeRepository repo = new FakeRepository();
    private long now = 1_000;
    private final ChatLinkLauncher launcher = new ChatLinkLauncher(repo, () -> now);

    /** 화면 하나(버튼 하나)의 결과 기록 */
    private static final class Screen implements ChatLinkLauncher.Listener {
        final List<String> events = new ArrayList<>();
        @Override public void onPreparing() { events.add("preparing"); }
        @Override public void onReady(String chatRoomId) { events.add("ready:" + chatRoomId); }
        @Override public void onFailed(ChatLinkLauncher.Failure failure) { events.add("failed:" + failure); }
    }

    private static Map<String, Object> details(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    @Test
    public void ready_opensReturnedRoom() {
        Screen screen = new Screen();
        launcher.start("u0", MATCH, screen);
        repo.last().onResult(BLOCKING, true); // 재사용 방이면 다른 방 키가 온다
        assertEquals(List.of("preparing", "ready:" + BLOCKING), screen.events);
    }

    @Test
    public void secondScreenWhileInFlight_isBusyAndDoesNotCallAgain() {
        Screen accepted = new Screen(), completedCard = new Screen();
        launcher.start("u0", MATCH, accepted);
        launcher.start("u0", MATCH, completedCard);
        assertEquals(1, repo.calls.size());
        assertEquals(List.of("failed:BUSY"), completedCard.events);
        repo.last().onResult(MATCH, true);
        assertEquals("ready:" + MATCH, accepted.events.get(1));
    }

    @Test
    public void retryAfter_isKeptAcrossScreens() {
        Screen first = new Screen();
        launcher.start("u0", MATCH, first);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_LINK_BUSY", "retryAfterMillis", 5_000)));
        assertEquals("failed:RETRY_LATER", first.events.get(1));

        Screen rotated = new Screen();
        now += 4_999;
        launcher.start("u0", MATCH, rotated);
        assertEquals(List.of("failed:BUSY"), rotated.events);
        assertEquals(1, repo.calls.size());

        now += 1;
        launcher.start("u0", MATCH, rotated);
        assertEquals(2, repo.calls.size());
    }

    @Test
    public void pairBusy_preparesBlockingMatchThenOriginalOnce() {
        Screen screen = new Screen();
        launcher.start("u0", MATCH, screen);
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION",
                details("reason", "CHAT_PAIR_BUSY", "blockingMatchId", BLOCKING)));
        repo.last().onResult(BLOCKING, true);
        repo.last().onResult(BLOCKING, true);
        assertEquals(List.of(MATCH, BLOCKING, MATCH), repo.calls);
        assertEquals(List.of("preparing", "ready:" + BLOCKING), screen.events);
    }

    @Test
    public void permanent_isRememberedWithoutCallingAgain() {
        launcher.start("u0", MATCH, new Screen());
        repo.last().onFailure(ChatLinkFailure.from("FAILED_PRECONDITION", details("reason", "INVALID_ROOM")));
        Screen again = new Screen();
        now += 1_000_000;
        launcher.start("u0", MATCH, again);
        assertEquals(List.of("failed:PERMANENT"), again.events);
        assertEquals(1, repo.calls.size());
    }

    @Test
    public void loginRequired_canRetryAfterSignInAgain() {
        Screen screen = new Screen();
        launcher.start("u0", MATCH, screen);
        repo.last().onFailure(ChatLinkFailure.from("UNAUTHENTICATED", null));
        assertEquals("failed:LOGIN_REQUIRED", screen.events.get(1));
        launcher.start("u0", MATCH, new Screen());
        assertEquals(2, repo.calls.size());
    }

    @Test
    public void archived_reportsAndAllowsNextTry() {
        Screen screen = new Screen();
        launcher.start("u0", MATCH, screen);
        repo.last().onResult(MATCH, false);
        assertEquals("failed:ARCHIVED", screen.events.get(1));
        launcher.start("u0", MATCH, new Screen());
        assertEquals(2, repo.calls.size());
    }

    @Test
    public void differentAccountsAndMatches_doNotShareState() {
        launcher.start("u0", MATCH, new Screen());
        launcher.start("u1", MATCH, new Screen());
        launcher.start("u0", BLOCKING, new Screen());
        assertEquals(3, repo.calls.size());
    }

    @Test
    public void synchronousMockRepository_finishesWithoutStayingBusy() {
        ChatLinkLauncher mock = new ChatLinkLauncher(ChatLinkRepositories.MOCK, () -> now);
        Screen first = new Screen(), second = new Screen();
        mock.start("u0", MATCH, first);
        mock.start("u0", MATCH, second);
        assertEquals(List.of("preparing", "ready:" + MATCH), first.events);
        assertEquals(List.of("preparing", "ready:" + MATCH), second.events);
    }
}
