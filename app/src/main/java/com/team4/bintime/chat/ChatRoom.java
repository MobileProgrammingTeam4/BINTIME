package com.team4.bintime.chat;

import java.util.List;

/**
 * BT-009 임시 모델. 매칭 필드는 AGENTS.md 5장의 matches 계약을 따르고, BT-012 확정 후 위치·이름을 맞춘다.
 * meetingDate, startMinute, endMinute 는 확정 시간 스냅샷이라 메시지가 오가도 바뀌지 않는다.
 */
public final class ChatRoom {
    public enum Status { ACTIVE, ENDED }

    /** 채팅 탭: 전체 / 매칭됨 / 매칭종료 */
    public enum Filter {
        ALL, MATCHED, ENDED;

        public boolean matches(ChatRoom room) {
            if (this == MATCHED) return room.status == Status.ACTIVE;
            if (this == ENDED) return room.status == Status.ENDED;
            return true;
        }
    }

    public final String matchId;
    public final List<String> participantUids;
    public final Status status;
    public final String meetingDate;
    public final int startMinute, endMinute;
    public final boolean chatReady;
    public final String otherUid, otherName;
    public final boolean otherHasPhoto;
    public final String lastMessage;
    public final long lastMessageAt;
    public final int unreadCount;
    /** 추천 장소가 없으면 null. BT-009 에서는 AI 결과가 아니라 Mock 문구다. */
    public final String recommendedPlace;
    public final boolean reviewWritten;

    ChatRoom(String matchId, List<String> participantUids, Status status, String meetingDate,
             int startMinute, int endMinute, boolean chatReady, String otherUid, String otherName,
             boolean otherHasPhoto, String lastMessage, long lastMessageAt, int unreadCount,
             String recommendedPlace, boolean reviewWritten) {
        this.matchId = matchId;
        this.participantUids = participantUids;
        this.status = status;
        this.meetingDate = meetingDate;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.chatReady = chatReady;
        this.otherUid = otherUid;
        this.otherName = otherName;
        this.otherHasPhoto = otherHasPhoto;
        this.lastMessage = lastMessage;
        this.lastMessageAt = lastMessageAt;
        this.unreadCount = unreadCount;
        this.recommendedPlace = recommendedPlace;
        this.reviewWritten = reviewWritten;
    }

    ChatRoom withLastMessage(String message, long at, int unread) {
        return new ChatRoom(matchId, participantUids, status, meetingDate, startMinute, endMinute,
                chatReady, otherUid, otherName, otherHasPhoto, message, at, unread,
                recommendedPlace, reviewWritten);
    }

    ChatRoom withUnread(int unread) {
        return withLastMessage(lastMessage, lastMessageAt, unread);
    }
}
