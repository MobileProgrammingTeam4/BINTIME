package com.team4.bintime.chat;

/**
 * RTDB userChatRooms/{uid}/{matchId} 한 항목. 사용자별 채팅 목록·읽지 않음 수이며 서버가 갱신한다.
 * status 는 Firestore matches.status 를 서버가 복사한 값이다. 원본과 다르면 원본(MatchInfo)을 따른다.
 */
final class UserChatRoomEntry {
    final String matchId, otherUid;
    /** 아직 메시지가 없으면 빈 문자열 */
    final String lastMessage;
    /** 아직 메시지가 없으면 0 */
    final long lastMessageAt;
    final int unreadCount;
    final ChatRoom.Status status;
    /** 채팅방 나가기(BT-043). true 면 내 목록에서 뺀다. */
    final boolean hidden;
    final boolean archived;
    /** null은 BT-033 이전의 필드 없는 방이다. 명시적 false는 매칭 문서보다 우선한다. */
    final Boolean chatReady;
    /** BT-034: 항목을 처음 만든 서버 시각(ms). 필드가 없는 이전 방은 0 */
    final long linkedAt;

    UserChatRoomEntry(String matchId, String otherUid, String lastMessage, long lastMessageAt,
                      int unreadCount, ChatRoom.Status status, boolean hidden) {
        this(matchId, otherUid, lastMessage, lastMessageAt, unreadCount, status, hidden, false);
    }
    UserChatRoomEntry(String matchId, String otherUid, String lastMessage, long lastMessageAt,
                      int unreadCount, ChatRoom.Status status, boolean hidden, boolean archived) {
        this(matchId, otherUid, lastMessage, lastMessageAt, unreadCount, status, hidden, archived, null);
    }
    UserChatRoomEntry(String matchId, String otherUid, String lastMessage, long lastMessageAt,
                      int unreadCount, ChatRoom.Status status, boolean hidden, boolean archived, Boolean chatReady) {
        this(matchId, otherUid, lastMessage, lastMessageAt, unreadCount, status, hidden, archived, chatReady, 0L);
    }
    UserChatRoomEntry(String matchId, String otherUid, String lastMessage, long lastMessageAt,
                      int unreadCount, ChatRoom.Status status, boolean hidden, boolean archived, Boolean chatReady,
                      long linkedAt) {
        this.matchId = matchId;
        this.otherUid = otherUid;
        this.lastMessage = lastMessage;
        this.lastMessageAt = lastMessageAt;
        this.unreadCount = unreadCount;
        this.status = status;
        this.hidden = hidden;
        this.archived = archived;
        this.chatReady = chatReady;
        this.linkedAt = linkedAt;
    }
}
