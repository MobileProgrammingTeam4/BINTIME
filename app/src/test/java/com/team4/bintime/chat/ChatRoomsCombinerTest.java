package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatRoomsCombinerTest {

    private static final String ME = "uid-me";

    /** 구독을 기록하고 테스트가 원할 때 값을 보내는 가짜 출처. */
    private static final class FakeSource<T> {
        final Map<String, ChatRepository.Listener<T>> active = new HashMap<>();
        final List<String> subscribed = new ArrayList<>();
        final List<String> released = new ArrayList<>();

        ChatRepository.Registration observe(String key, ChatRepository.Listener<T> listener) {
            subscribed.add(key);
            active.put(key, listener);
            return () -> {
                released.add(key);
                active.remove(key);
            };
        }

        void push(String key, T value) {
            active.get(key).onData(value);
        }

        void fail(String key) {
            active.get(key).onError();
        }
    }

    private FakeSource<MatchInfo> matches;
    private FakeSource<PartnerInfo> partners;
    private List<List<ChatRoom>> emitted;
    private ChatRoomsCombiner combiner;

    @Before
    public void setUp() {
        matches = new FakeSource<>();
        partners = new FakeSource<>();
        emitted = new ArrayList<>();
        combiner = new ChatRoomsCombiner(ME, "상대", matches::observe, partners::observe, emitted::add);
    }

    private static UserChatRoomEntry entry(String matchId, String otherUid, long at, boolean hidden) {
        return new UserChatRoomEntry(matchId, otherUid, "msg-" + matchId, at, 0, ChatRoom.Status.ACTIVE, hidden);
    }

    private List<ChatRoom> last() {
        return emitted.get(emitted.size() - 1);
    }

    @Test
    public void nothingEmittedBeforeFirstUpdate() {
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void emptyListIsEmitted() {
        combiner.update(Collections.emptyList());
        assertEquals(1, emitted.size());
        assertTrue(last().isEmpty());
    }

    @Test
    public void emitsRoomsImmediatelyWithFallbackThenEnrichesWhenSourcesDeliver() {
        combiner.update(Collections.singletonList(entry("m1", "u1", 100L, false)));
        assertEquals(1, emitted.size());
        assertEquals("상대", last().get(0).otherName);
        assertNull(last().get(0).meetingDate);

        matches.push("m1", new MatchInfo("m1", Arrays.asList(ME, "u1"), ChatRoom.Status.ENDED,
                "2026-11-12", 600, 690, true));
        assertEquals(ChatRoom.Status.ENDED, last().get(0).status);
        assertEquals("2026-11-12", last().get(0).meetingDate);

        partners.push("u1", new PartnerInfo("u1", "지민", null, false));
        assertEquals("지민", last().get(0).otherName);
        assertEquals(3, emitted.size());
    }

    @Test
    public void synchronousSourcesEmitOncePerUpdate() {
        ChatRoomsCombiner sync = new ChatRoomsCombiner(ME, "상대", MatchInfoSource.UNAVAILABLE,
                PartnerSource.UNAVAILABLE, emitted::add);
        sync.update(Arrays.asList(entry("m1", "u1", 100L, false), entry("m2", "u2", 200L, false)));
        assertEquals(1, emitted.size());
        assertEquals("m2", last().get(0).matchId);
    }

    @Test
    public void hiddenRoomsAreNotSubscribedOrEmitted() {
        combiner.update(Arrays.asList(entry("m1", "u1", 100L, false), entry("m-left", "u2", 300L, true)));
        assertEquals(Collections.singletonList("m1"), matches.subscribed);
        assertEquals(Collections.singletonList("u1"), partners.subscribed);
        assertEquals(1, last().size());
    }

    @Test
    public void subscribesOncePerKeyAndReleasesRemovedRooms() {
        combiner.update(Arrays.asList(entry("m1", "u1", 100L, false), entry("m2", "u1", 200L, false)));
        combiner.update(Arrays.asList(entry("m1", "u1", 150L, false), entry("m2", "u1", 250L, false)));
        assertEquals(Arrays.asList("m1", "m2"), sorted(matches.subscribed));
        assertEquals(Collections.singletonList("u1"), partners.subscribed);

        combiner.update(Collections.singletonList(entry("m2", "u1", 300L, false)));
        assertEquals(Collections.singletonList("m1"), matches.released);
        assertTrue(partners.released.isEmpty());
        assertEquals(1, last().size());
    }

    @Test
    public void sourceErrorKeepsRoomWithoutThatInfo() {
        combiner.update(Collections.singletonList(entry("m1", "u1", 100L, false)));
        matches.push("m1", new MatchInfo("m1", Arrays.asList(ME, "u1"), ChatRoom.Status.ACTIVE,
                "2026-11-12", 600, 690, true));
        matches.fail("m1");
        assertEquals(1, last().size());
        assertNull(last().get(0).meetingDate);
    }

    @Test
    public void releaseRemovesAllAndIgnoresLateCallbacks() {
        combiner.update(Collections.singletonList(entry("m1", "u1", 100L, false)));
        ChatRepository.Listener<MatchInfo> lateMatch = matches.active.get("m1");
        int before = emitted.size();
        combiner.release();
        assertEquals(Collections.singletonList("m1"), matches.released);
        assertEquals(Collections.singletonList("u1"), partners.released);

        lateMatch.onData(new MatchInfo("m1", Arrays.asList(ME, "u1"), ChatRoom.Status.ACTIVE,
                "2026-11-12", 600, 690, true));
        combiner.update(Collections.singletonList(entry("m2", "u2", 100L, false)));
        assertEquals(before, emitted.size());
    }

    /** #90 리뷰 Y1: ACTIVE 구독 → 방 제거 → 같은 방 ENDED 재구독 → 옛 구독의 ACTIVE 콜백 도착. */
    @Test
    public void lateCallbackFromReleasedMatchSubscriptionDoesNotOverwriteResubscribedRoom() {
        combiner.update(Collections.singletonList(entry("m1", "u1", 100L, false)));
        ChatRepository.Listener<MatchInfo> stale = matches.active.get("m1");

        combiner.update(Collections.emptyList());
        combiner.update(Collections.singletonList(entry("m1", "u1", 200L, false)));
        matches.push("m1", new MatchInfo("m1", Arrays.asList(ME, "u1"), ChatRoom.Status.ENDED,
                "2026-11-12", 600, 690, true));
        assertEquals(ChatRoom.Status.ENDED, last().get(0).status);

        int before = emitted.size();
        stale.onData(new MatchInfo("m1", Arrays.asList(ME, "u1"), ChatRoom.Status.ACTIVE,
                "2026-11-12", 600, 690, true));
        stale.onError();
        assertEquals(before, emitted.size());
        assertEquals(ChatRoom.Status.ENDED, last().get(0).status);
        assertEquals("2026-11-12", last().get(0).meetingDate);
    }

    @Test
    public void lateCallbackFromReleasedPartnerSubscriptionIsIgnored() {
        combiner.update(Collections.singletonList(entry("m1", "u1", 100L, false)));
        ChatRepository.Listener<PartnerInfo> stale = partners.active.get("u1");

        combiner.update(Collections.singletonList(entry("m2", "u2", 200L, false)));
        combiner.update(Arrays.asList(entry("m2", "u2", 200L, false), entry("m3", "u1", 300L, false)));
        partners.push("u1", new PartnerInfo("u1", "새이름", null, false));

        stale.onData(new PartnerInfo("u1", "옛이름", null, false));
        assertEquals("새이름", last().get(0).otherName);
        assertEquals(Arrays.asList("u1", "u1", "u2"), sorted(partners.subscribed));
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }
}
