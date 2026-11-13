package com.team4.bintime.chat;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * RTDB 값(DataSnapshot.getValue() 결과: Map·String·Long·Double·Boolean)을 채팅 모델로 바꾼다.
 * Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다. 형식이 틀린 노드는 null 로 건너뛰고 화면 전체를 깨뜨리지 않는다.
 */
final class ChatRtdbMapper {

    private ChatRtdbMapper() {}

    /** messages/{pushId} 하나. 필수 필드가 없거나 형식이 틀리면 null. */
    @Nullable
    static ChatMessage message(@Nullable String messageId, @Nullable Object value) {
        if (isBlank(messageId) || !(value instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) value;
        String senderId = string(map.get(ChatRtdbPaths.SENDER_ID));
        String content = string(map.get(ChatRtdbPaths.CONTENT));
        Long createdAt = positiveLong(map.get(ChatRtdbPaths.CREATED_AT));
        if (isBlank(senderId) || isBlank(content) || createdAt == null) return null;
        if (content.length() > ChatRtdbPaths.MAX_CONTENT_LENGTH) return null;
        return new ChatMessage(messageId, senderId, content, createdAt);
    }

    /** 오래된 메시지부터. 같은 시각이면 push 키 순서(생성 순)로 정렬한다. */
    static List<ChatMessage> sortMessages(List<ChatMessage> messages) {
        List<ChatMessage> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.<ChatMessage>comparingLong(m -> m.createdAt).thenComparing(m -> m.messageId));
        return sorted;
    }

    /** userChatRooms/{uid}/{matchId} 하나. otherUid·status 가 없거나 형식이 틀리면 null. */
    @Nullable
    static UserChatRoomEntry userChatRoom(@Nullable String matchId, @Nullable Object value) {
        if (isBlank(matchId) || !(value instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) value;
        String otherUid = string(map.get(ChatRtdbPaths.OTHER_UID));
        ChatRoom.Status status = status(map.get(ChatRtdbPaths.STATUS));
        if (isBlank(otherUid) || status == null) return null;
        String lastMessage = string(map.get(ChatRtdbPaths.LAST_MESSAGE));
        Long lastMessageAt = positiveLong(map.get(ChatRtdbPaths.LAST_MESSAGE_AT));
        Long unread = nonNegativeLong(map.get(ChatRtdbPaths.UNREAD_COUNT));
        Object hidden = map.get(ChatRtdbPaths.HIDDEN);
        return new UserChatRoomEntry(matchId, otherUid,
                lastMessage == null ? "" : lastMessage,
                lastMessageAt == null ? 0L : lastMessageAt,
                unread == null ? 0 : (int) Math.min(unread, Integer.MAX_VALUE),
                status,
                Boolean.TRUE.equals(hidden));
    }

    /**
     * 목록에 보일 항목만 최근 메시지 순으로. 나간 방(hidden)은 뺀다.
     * 메시지가 없는 방은 뒤로 보내고, 시각이 같으면 matchId 로 순서를 고정한다.
     */
    static List<UserChatRoomEntry> visibleRooms(List<UserChatRoomEntry> entries) {
        List<UserChatRoomEntry> visible = new ArrayList<>();
        for (UserChatRoomEntry entry : entries) if (!entry.hidden) visible.add(entry);
        visible.sort(Comparator.<UserChatRoomEntry>comparingLong(e -> e.lastMessageAt).reversed()
                .thenComparing(e -> e.matchId));
        return visible;
    }

    /**
     * 화면용 ChatRoom 을 만든다. 상태·시간은 매칭 스냅샷(match)이 원본이고, 없으면 목록 미러 상태를 쓴다.
     * match 가 없으면(BT-034 전, 또는 매칭 문서 아직 없음) meetingDate 는 null, 시간은 0, chatReady 는 false 다.
     * partner 가 없으면(#79 전) fallbackName 을 이름으로 쓴다.
     */
    static ChatRoom room(String myUid, UserChatRoomEntry entry, @Nullable MatchInfo match,
                         @Nullable PartnerInfo partner, String fallbackName) {
        List<String> participants = match != null
                ? match.participantUids
                : Collections.unmodifiableList(Arrays.asList(myUid, entry.otherUid));
        ChatRoom.Status status = match != null ? match.status : entry.status;
        boolean samePartner = partner != null && entry.otherUid.equals(partner.uid);
        return new ChatRoom(entry.matchId, participants, status,
                match != null ? match.meetingDate : null,
                match != null ? match.startMinute : 0,
                match != null ? match.endMinute : 0,
                match != null && match.chatReady,
                entry.otherUid,
                samePartner ? partner.name : fallbackName,
                samePartner ? partner.info : null,
                samePartner && partner.hasPhoto,
                entry.lastMessage, entry.lastMessageAt, entry.unreadCount,
                null /* recommendedPlace: BT-042 */);
    }

    @Nullable
    private static ChatRoom.Status status(@Nullable Object value) {
        String text = string(value);
        if (text == null) return null;
        for (ChatRoom.Status status : ChatRoom.Status.values()) {
            if (status.name().equals(text)) return status;
        }
        return null;
    }

    @Nullable
    private static String string(@Nullable Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** RTDB 숫자는 Long 또는 Double 로 온다. 소수점이 있거나 0 이하면 null. */
    @Nullable
    private static Long positiveLong(@Nullable Object value) {
        Long number = wholeNumber(value);
        return number != null && number > 0 ? number : null;
    }

    @Nullable
    private static Long nonNegativeLong(@Nullable Object value) {
        Long number = wholeNumber(value);
        return number != null && number >= 0 ? number : null;
    }

    @Nullable
    private static Long wholeNumber(@Nullable Object value) {
        if (value instanceof Long || value instanceof Integer) return ((Number) value).longValue();
        if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isFinite(d) && d == Math.rint(d)) return (long) d;
        }
        return null;
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }
}
