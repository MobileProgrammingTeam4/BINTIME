package com.team4.bintime.chat;

import java.util.Collections;
import java.util.List;

/**
 * Firestore matches/{matchId} 확정 스냅샷 중 채팅방이 쓰는 값. 매칭 상태와 시간의 원본이다.
 * meetingDate·startMinute·endMinute 는 생성 후 바뀌지 않는다.
 */
public final class MatchInfo {
    public final List<com.team4.bintime.request.MatchAppointment> appointments;
    public final String matchId;
    public final List<String> participantUids;
    public final ChatRoom.Status status;
    /** YYYY-MM-DD, Asia/Seoul */
    public final String meetingDate;
    public final int startMinute, endMinute;
    public final boolean chatReady;

    public MatchInfo(String matchId, List<String> participantUids, ChatRoom.Status status,
                     String meetingDate, int startMinute, int endMinute, boolean chatReady) {
        this(matchId, participantUids, status, meetingDate, startMinute, endMinute, chatReady, Collections.emptyList());
    }
    public MatchInfo(String matchId, List<String> participantUids, ChatRoom.Status status,
                     String meetingDate, int startMinute, int endMinute, boolean chatReady,
                     List<com.team4.bintime.request.MatchAppointment> appointments) {
        this.matchId = matchId;
        this.participantUids = Collections.unmodifiableList(participantUids);
        this.status = status;
        this.meetingDate = meetingDate;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.chatReady = chatReady;
        this.appointments = Collections.unmodifiableList(new java.util.ArrayList<>(appointments));
    }
}
