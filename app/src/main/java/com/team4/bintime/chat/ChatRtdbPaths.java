package com.team4.bintime.chat;

/**
 * BT-021 RTDB 경로·필드 이름. docs/BT-012.md 의 RTDB 계약과 database.rules.json 이 같은 이름을 쓴다.
 * <pre>
 * chatRooms/{matchId}/members/{uid}:     { joinedAt }
 * chatRooms/{matchId}/messages/{pushId}: { senderId, content, createdAt }
 * userChatRooms/{uid}/{matchId}:         { otherUid, lastMessage, lastMessageAt, unreadCount, status, hidden }
 * </pre>
 * matchId 는 matches/{matchId} 문서 ID(= requestId)이고, messageId 는 push 키를 그대로 쓴다(노드 안에 저장하지 않음).
 */
final class ChatRtdbPaths {

    static final String CHAT_ROOMS = "chatRooms";
    static final String MEMBERS = "members";
    static final String MESSAGES = "messages";
    static final String USER_CHAT_ROOMS = "userChatRooms";

    // messages/{pushId}
    static final String SENDER_ID = "senderId";
    static final String CONTENT = "content";
    static final String CREATED_AT = "createdAt";

    // userChatRooms/{uid}/{matchId}
    static final String OTHER_UID = "otherUid";
    static final String LAST_MESSAGE = "lastMessage";
    static final String LAST_MESSAGE_AT = "lastMessageAt";
    static final String UNREAD_COUNT = "unreadCount";
    static final String STATUS = "status";
    static final String HIDDEN = "hidden";

    /** 메시지 본문 최대 길이. database.rules.json 의 검증 값과 같아야 한다. */
    static final int MAX_CONTENT_LENGTH = 1000;

    /** 채팅방 화면이 처음 구독할 최근 메시지 수. 이전 메시지 페이징은 후속 이슈에서 정한다. */
    static final int MESSAGE_PAGE_SIZE = 100;

    private ChatRtdbPaths() {}

    static String members(String matchId) {
        return CHAT_ROOMS + "/" + matchId + "/" + MEMBERS;
    }

    static String messages(String matchId) {
        return CHAT_ROOMS + "/" + matchId + "/" + MESSAGES;
    }

    static String userChatRooms(String uid) {
        return USER_CHAT_ROOMS + "/" + uid;
    }
}
