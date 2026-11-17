package com.team4.bintime.chat;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import com.team4.bintime.request.MatchAppointment;

/**
 * BT-009·BT-010 임시 모델. 매칭 필드는 AGENTS.md 5장의 matches 계약을 따르고, BT-012 확정 후 위치·이름을 맞춘다.
 * meetingDate, startMinute, endMinute 는 확정 시간 스냅샷이라 메시지가 오가거나 매칭이 종료돼도 바뀌지 않는다.
 * 후기 작성 여부는 이 모델이 아니라 review.ReviewRepository 가 기준이다.
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
    /** 기존 API의 matchId는 RTDB 방 키다. 개별 약속 ID는 appointments에서 가져온다. */
    public final List<MatchAppointment> appointments;
    public final boolean archived;
    public final List<String> participantUids;
    public final Status status;
    public final String meetingDate;
    public final int startMinute, endMinute;
    public final boolean chatReady;
    public final String otherUid, otherName;
    /** 학과·학년·성별 요약. 예: 컴퓨터공학과 3학년 · 여성. 실제 값은 publicProfiles 에서 받는다. */
    public final String otherInfo;
    public final boolean otherHasPhoto;
    public final String lastMessage;
    public final long lastMessageAt;
    public final int unreadCount;
    /** 추천 장소가 없으면 null. BT-009 에서는 AI 결과가 아니라 Mock 문구다. */
    public final String recommendedPlace;

    ChatRoom(String matchId, List<String> participantUids, Status status, String meetingDate,
             int startMinute, int endMinute, boolean chatReady, String otherUid, String otherName,
             String otherInfo, boolean otherHasPhoto, String lastMessage, long lastMessageAt,
             int unreadCount, String recommendedPlace) {
        this(matchId, participantUids, status, meetingDate, startMinute, endMinute, chatReady, otherUid, otherName,
                otherInfo, otherHasPhoto, lastMessage, lastMessageAt, unreadCount, recommendedPlace, Collections.emptyList(), false);
    }

    private ChatRoom(String matchId, List<String> participantUids, Status status, String meetingDate,
             int startMinute, int endMinute, boolean chatReady, String otherUid, String otherName,
             String otherInfo, boolean otherHasPhoto, String lastMessage, long lastMessageAt,
             int unreadCount, String recommendedPlace, List<MatchAppointment> appointments, boolean archived) {
        this.matchId = matchId;
        this.participantUids = participantUids;
        this.status = status;
        this.meetingDate = meetingDate;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.chatReady = chatReady;
        this.otherUid = otherUid;
        this.otherName = otherName;
        this.otherInfo = otherInfo;
        this.otherHasPhoto = otherHasPhoto;
        this.lastMessage = lastMessage;
        this.lastMessageAt = lastMessageAt;
        this.unreadCount = unreadCount;
        this.recommendedPlace = recommendedPlace;
        this.appointments = Collections.unmodifiableList(new ArrayList<>(appointments));
        this.archived = archived;
    }

    ChatRoom withLastMessage(String message, long at, int unread) {
        return new ChatRoom(matchId, participantUids, status, meetingDate, startMinute, endMinute,
                chatReady, otherUid, otherName, otherInfo, otherHasPhoto, message, at, unread,
                recommendedPlace, appointments, archived);
    }

    ChatRoom withUnread(int unread) {
        return withLastMessage(lastMessage, lastMessageAt, unread);
    }

    /** 상태만 바꾼다. 확정 시간 스냅샷은 그대로 둔다. */
    ChatRoom withStatus(Status newStatus) {
        return new ChatRoom(matchId, participantUids, newStatus, meetingDate, startMinute, endMinute,
                chatReady, otherUid, otherName, otherInfo, otherHasPhoto, lastMessage, lastMessageAt,
                unreadCount, recommendedPlace, appointments, archived);
    }

    ChatRoom withAppointments(List<MatchAppointment> items, boolean archived) {
        Status aggregate = items.isEmpty() ? status : items.stream().anyMatch(a -> a.status == MatchAppointment.Status.ACTIVE) ? Status.ACTIVE : Status.ENDED;
        return new ChatRoom(matchId, participantUids, archived ? Status.ENDED : aggregate, meetingDate, startMinute, endMinute,
                chatReady && !archived, otherUid, otherName, otherInfo, otherHasPhoto, lastMessage, lastMessageAt,
                unreadCount, recommendedPlace, items, archived);
    }
    ChatRoom withDisplayAppointment(MatchAppointment item) {
        return new ChatRoom(matchId, participantUids, status, item.meetingDate, item.startMinute, item.endMinute, chatReady,
                otherUid, otherName, otherInfo, otherHasPhoto, lastMessage, lastMessageAt, unreadCount, recommendedPlace, appointments, archived);
    }
}
