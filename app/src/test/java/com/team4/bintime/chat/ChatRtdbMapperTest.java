package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatRtdbMapperTest {

    private static final String ME = "uid-me";
    private static final String OTHER = "uid-other";
    private static final String MATCH = "a".repeat(64);

    private static Map<String, Object> messageNode(Object senderId, Object content, Object createdAt) {
        Map<String, Object> node = new HashMap<>();
        if (senderId != null) node.put("senderId", senderId);
        if (content != null) node.put("content", content);
        if (createdAt != null) node.put("createdAt", createdAt);
        return node;
    }

    private static Map<String, Object> roomNode(String otherUid, String status) {
        Map<String, Object> node = new HashMap<>();
        if (otherUid != null) node.put("otherUid", otherUid);
        if (status != null) node.put("status", status);
        return node;
    }

    // ---- messages ----

    @Test
    public void message_readsAllFieldsAndUsesPushKeyAsId() {
        ChatMessage m = ChatRtdbMapper.message("-Nabc", messageNode(ME, "안녕하세요", 1_700_000_000_000L));
        assertNotNull(m);
        assertEquals("-Nabc", m.messageId);
        assertEquals(ME, m.senderId);
        assertEquals("안녕하세요", m.content);
        assertEquals(1_700_000_000_000L, m.createdAt);
    }

    @Test
    public void message_acceptsWholeDoubleTimestamp() {
        ChatMessage m = ChatRtdbMapper.message("k", messageNode(ME, "hi", 1_700_000_000_000d));
        assertNotNull(m);
        assertEquals(1_700_000_000_000L, m.createdAt);
    }

    @Test
    public void message_rejectsMissingOrInvalidFields() {
        assertNull(ChatRtdbMapper.message("k", messageNode(null, "hi", 1L)));
        assertNull(ChatRtdbMapper.message("k", messageNode(" ", "hi", 1L)));
        assertNull(ChatRtdbMapper.message("k", messageNode(ME, null, 1L)));
        assertNull(ChatRtdbMapper.message("k", messageNode(ME, "   ", 1L)));
        assertNull(ChatRtdbMapper.message("k", messageNode(ME, "hi", null)));
        assertNull(ChatRtdbMapper.message("k", messageNode(ME, "hi", 0L)));
        assertNull(ChatRtdbMapper.message("k", messageNode(ME, "hi", -5L)));
        assertNull(ChatRtdbMapper.message("k", messageNode(ME, "hi", 1.5d)));
        assertNull(ChatRtdbMapper.message("k", messageNode(ME, "hi", "1700000000000")));
        assertNull(ChatRtdbMapper.message("k", messageNode(123L, "hi", 1L)));
    }

    @Test
    public void message_rejectsNonMapOrMissingKey() {
        assertNull(ChatRtdbMapper.message("k", null));
        assertNull(ChatRtdbMapper.message("k", "text"));
        assertNull(ChatRtdbMapper.message(null, messageNode(ME, "hi", 1L)));
        assertNull(ChatRtdbMapper.message("", messageNode(ME, "hi", 1L)));
    }

    @Test
    public void message_contentLengthBoundary() {
        String max = "가".repeat(ChatRtdbPaths.MAX_CONTENT_LENGTH);
        assertNotNull(ChatRtdbMapper.message("k", messageNode(ME, max, 1L)));
        assertNull(ChatRtdbMapper.message("k", messageNode(ME, max + "가", 1L)));
    }

    @Test
    public void message_ignoresUnknownFields() {
        Map<String, Object> node = messageNode(ME, "hi", 1L);
        node.put("type", "SYSTEM");
        assertNotNull(ChatRtdbMapper.message("k", node));
    }

    @Test
    public void sortMessages_oldestFirstThenByPushKey() {
        ChatMessage late = new ChatMessage("-b", ME, "3", 300L);
        ChatMessage sameTimeB = new ChatMessage("-b2", OTHER, "2", 200L);
        ChatMessage sameTimeA = new ChatMessage("-a2", ME, "1", 200L);
        ChatMessage early = new ChatMessage("-z", OTHER, "0", 100L);
        List<ChatMessage> sorted = ChatRtdbMapper.sortMessages(Arrays.asList(late, sameTimeB, sameTimeA, early));
        assertEquals(Arrays.asList("-z", "-a2", "-b2", "-b"),
                Arrays.asList(sorted.get(0).messageId, sorted.get(1).messageId,
                        sorted.get(2).messageId, sorted.get(3).messageId));
    }

    // ---- userChatRooms ----

    @Test
    public void userChatRoom_readsAllFields() {
        Map<String, Object> node = roomNode(OTHER, "ACTIVE");
        node.put("lastMessage", "곧 도착해요");
        node.put("lastMessageAt", 1_700_000_000_000L);
        node.put("unreadCount", 3L);
        node.put("hidden", false);
        UserChatRoomEntry e = ChatRtdbMapper.userChatRoom(MATCH, node);
        assertNotNull(e);
        assertEquals(MATCH, e.matchId);
        assertEquals(OTHER, e.otherUid);
        assertEquals("곧 도착해요", e.lastMessage);
        assertEquals(1_700_000_000_000L, e.lastMessageAt);
        assertEquals(3, e.unreadCount);
        assertEquals(ChatRoom.Status.ACTIVE, e.status);
        assertFalse(e.hidden);
    }

    @Test
    public void userChatRoom_newRoomWithoutMessagesUsesDefaults() {
        UserChatRoomEntry e = ChatRtdbMapper.userChatRoom(MATCH, roomNode(OTHER, "ENDED"));
        assertNotNull(e);
        assertEquals("", e.lastMessage);
        assertEquals(0L, e.lastMessageAt);
        assertEquals(0, e.unreadCount);
        assertEquals(ChatRoom.Status.ENDED, e.status);
        assertFalse(e.hidden);
    }

    @Test
    public void userChatRoom_rejectsMissingOtherUidOrUnknownStatus() {
        assertNull(ChatRtdbMapper.userChatRoom(MATCH, roomNode(null, "ACTIVE")));
        assertNull(ChatRtdbMapper.userChatRoom(MATCH, roomNode(OTHER, null)));
        assertNull(ChatRtdbMapper.userChatRoom(MATCH, roomNode(OTHER, "active")));
        assertNull(ChatRtdbMapper.userChatRoom(MATCH, roomNode(OTHER, "CANCELED")));
        assertNull(ChatRtdbMapper.userChatRoom(MATCH, "ACTIVE"));
        assertNull(ChatRtdbMapper.userChatRoom(null, roomNode(OTHER, "ACTIVE")));
    }

    @Test
    public void userChatRoom_invalidOptionalFieldsFallBackToDefaults() {
        Map<String, Object> node = roomNode(OTHER, "ACTIVE");
        node.put("unreadCount", -1L);
        node.put("lastMessageAt", "yesterday");
        node.put("hidden", "true");
        UserChatRoomEntry e = ChatRtdbMapper.userChatRoom(MATCH, node);
        assertNotNull(e);
        assertEquals(0, e.unreadCount);
        assertEquals(0L, e.lastMessageAt);
        assertFalse(e.hidden);
    }

    @Test
    public void visibleRooms_excludesHiddenAndSortsRecentFirst() {
        UserChatRoomEntry old = new UserChatRoomEntry("m-old", OTHER, "a", 100L, 0, ChatRoom.Status.ENDED, false);
        UserChatRoomEntry recent = new UserChatRoomEntry("m-new", OTHER, "b", 300L, 1, ChatRoom.Status.ACTIVE, false);
        UserChatRoomEntry left = new UserChatRoomEntry("m-left", OTHER, "c", 500L, 0, ChatRoom.Status.ENDED, true);
        UserChatRoomEntry empty = new UserChatRoomEntry("m-empty", OTHER, "", 0L, 0, ChatRoom.Status.ACTIVE, false);
        List<UserChatRoomEntry> visible = ChatRtdbMapper.visibleRooms(Arrays.asList(old, left, empty, recent));
        assertEquals(3, visible.size());
        assertEquals("m-new", visible.get(0).matchId);
        assertEquals("m-old", visible.get(1).matchId);
        assertEquals("m-empty", visible.get(2).matchId);
    }

    @Test
    public void visibleRooms_emptyRoomUsesLinkedAtAndLegacyRoomGoesLast() {
        // 메시지 있는 방(300), 방금 연결한 메시지 없는 방(linkedAt 500), linkedAt 없는 이전 빈 방(0)
        UserChatRoomEntry withMessage = new UserChatRoomEntry("m-msg", OTHER, "b", 300L, 0, ChatRoom.Status.ACTIVE, false, false, true, 100L);
        UserChatRoomEntry fresh = new UserChatRoomEntry("m-fresh", OTHER, "", 0L, 0, ChatRoom.Status.ACTIVE, false, false, true, 500L);
        UserChatRoomEntry legacy = new UserChatRoomEntry("m-legacy", OTHER, "", 0L, 0, ChatRoom.Status.ACTIVE, false);
        UserChatRoomEntry olderFresh = new UserChatRoomEntry("m-older", OTHER, "", 0L, 0, ChatRoom.Status.ACTIVE, false, false, true, 200L);
        List<UserChatRoomEntry> visible = ChatRtdbMapper.visibleRooms(Arrays.asList(legacy, withMessage, olderFresh, fresh));
        assertEquals(Arrays.asList("m-fresh", "m-msg", "m-older", "m-legacy"),
                Arrays.asList(visible.get(0).matchId, visible.get(1).matchId, visible.get(2).matchId, visible.get(3).matchId));
    }

    @Test
    public void sortTime_prefersLastMessageThenLinkedAt() {
        assertEquals(300L, ChatRtdbMapper.sortTime(300L, 900L)); // 메시지가 있으면 linkedAt 보다 메시지 시각
        assertEquals(900L, ChatRtdbMapper.sortTime(0L, 900L));
        assertEquals(0L, ChatRtdbMapper.sortTime(0L, 0L));
        assertEquals(0L, ChatRtdbMapper.sortTime(0L, -5L));
    }

    @Test
    public void userChatRoom_readsLinkedAtAndToleratesMissingOrInvalid() {
        Map<String, Object> node = roomNode(OTHER, "ACTIVE");
        node.put("linkedAt", 1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, ChatRtdbMapper.userChatRoom(MATCH, node).linkedAt);
        Map<String, Object> legacy = roomNode(OTHER, "ACTIVE");
        assertEquals(0L, ChatRtdbMapper.userChatRoom(MATCH, legacy).linkedAt);
        Map<String, Object> invalid = roomNode(OTHER, "ACTIVE");
        invalid.put("linkedAt", "어제");
        UserChatRoomEntry parsed = ChatRtdbMapper.userChatRoom(MATCH, invalid);
        assertEquals(0L, parsed.linkedAt); // 잘못된 값은 0, 방 자체는 정상 표시
        assertEquals(OTHER, parsed.otherUid);
    }

    // ---- ChatRoom 조립 ----

    @Test public void roomLevelNotReadyOverridesPreviouslyReadyMatchAndRecovers() {
        MatchInfo oldReady = new MatchInfo(MATCH, Arrays.asList(ME, OTHER), ChatRoom.Status.ACTIVE, "2026-11-17", 600, 690, true);
        Map<String, Object> node = roomNode(OTHER, "ACTIVE"); node.put("chatReady", false);
        assertFalse(ChatRtdbMapper.room(ME, ChatRtdbMapper.userChatRoom(MATCH, node), oldReady, null, "상대").chatReady);
        node.put("chatReady", true);
        assertTrue(ChatRtdbMapper.room(ME, ChatRtdbMapper.userChatRoom(MATCH, node), oldReady, null, "상대").chatReady);
        node.put("archived", true);
        assertFalse(ChatRtdbMapper.room(ME, ChatRtdbMapper.userChatRoom(MATCH, node), oldReady, null, "상대").chatReady);
    }
    @Test public void malformedReadinessFailsClosedWhileLegacyMissingFieldUsesMatch() {
        MatchInfo ready = new MatchInfo(MATCH, Arrays.asList(ME, OTHER), ChatRoom.Status.ACTIVE, "2026-11-17", 600, 690, true);
        Map<String, Object> node = roomNode(OTHER, "ACTIVE"); node.put("chatReady", "true");
        assertFalse(ChatRtdbMapper.room(ME, ChatRtdbMapper.userChatRoom(MATCH, node), ready, null, "상대").chatReady);
        node.remove("chatReady");
        assertTrue(ChatRtdbMapper.room(ME, ChatRtdbMapper.userChatRoom(MATCH, node), ready, null, "상대").chatReady);
    }

    @Test
    public void room_usesMatchSnapshotAsSourceOfStatusAndTime() {
        UserChatRoomEntry entry = new UserChatRoomEntry(MATCH, OTHER, "hi", 100L, 2, ChatRoom.Status.ACTIVE, false);
        MatchInfo match = new MatchInfo(MATCH, Arrays.asList(OTHER, ME), ChatRoom.Status.ENDED,
                "2026-11-12", 840, 930, true);
        PartnerInfo partner = new PartnerInfo(OTHER, "지민", "컴퓨터공학과 3학년", true);
        ChatRoom room = ChatRtdbMapper.room(ME, entry, match, partner, "상대");
        assertEquals(ChatRoom.Status.ENDED, room.status);
        assertEquals("2026-11-12", room.meetingDate);
        assertEquals(840, room.startMinute);
        assertEquals(930, room.endMinute);
        assertTrue(room.chatReady);
        assertEquals(Arrays.asList(OTHER, ME), room.participantUids);
        assertEquals("지민", room.otherName);
        assertEquals("컴퓨터공학과 3학년", room.otherInfo);
        assertTrue(room.otherHasPhoto);
        assertEquals("hi", room.lastMessage);
        assertEquals(2, room.unreadCount);
        assertNull(room.recommendedPlace);
    }

    @Test
    public void room_withoutMatchOrPartnerUsesMirrorStatusAndFallbackName() {
        UserChatRoomEntry entry = new UserChatRoomEntry(MATCH, OTHER, "", 0L, 0, ChatRoom.Status.ACTIVE, false);
        ChatRoom room = ChatRtdbMapper.room(ME, entry, null, null, "상대");
        assertEquals(ChatRoom.Status.ACTIVE, room.status);
        assertNull(room.meetingDate);
        assertEquals(0, room.startMinute);
        assertEquals(0, room.endMinute);
        assertFalse(room.chatReady);
        assertEquals(Arrays.asList(ME, OTHER), room.participantUids);
        assertEquals("상대", room.otherName);
        assertNull(room.otherInfo);
        assertFalse(room.otherHasPhoto);
    }

    @Test
    public void room_ignoresPartnerProfileOfDifferentUser() {
        UserChatRoomEntry entry = new UserChatRoomEntry(MATCH, OTHER, "", 0L, 0, ChatRoom.Status.ACTIVE, false);
        PartnerInfo stranger = new PartnerInfo("uid-stranger", "다른 사람", null, true);
        ChatRoom room = ChatRtdbMapper.room(ME, entry, null, stranger, "상대");
        assertEquals("상대", room.otherName);
        assertFalse(room.otherHasPhoto);
    }

    @Test
    public void unavailableSources_deliverNullOnce() {
        Object[] received = {"unset", "unset"};
        MatchInfoSource.UNAVAILABLE.observe(MATCH, new ChatRepository.Listener<MatchInfo>() {
            @Override public void onData(MatchInfo data) { received[0] = data; }
            @Override public void onError() {}
        }).remove();
        PartnerSource.UNAVAILABLE.observe(OTHER, new ChatRepository.Listener<PartnerInfo>() {
            @Override public void onData(PartnerInfo data) { received[1] = data; }
            @Override public void onError() {}
        }).remove();
        assertNull(received[0]);
        assertNull(received[1]);
        assertEquals(Collections.emptyList(), ChatRtdbMapper.visibleRooms(Collections.emptyList()));
    }
}
