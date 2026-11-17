package com.team4.bintime.request;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZoneId;

/** 방이 아닌 개별 확정 약속. reviewEligible은 BT-038 서버가 종료 시 확정하는 값이다. */
public final class MatchAppointment implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Status { ACTIVE, ENDED }
    public final String matchId, chatRoomId, meetingDate, purpose;
    public final int startMinute, endMinute;
    public final Status status;
    public final Boolean reviewEligible;
    public final boolean reviewWritten;

    public MatchAppointment(String matchId, String chatRoomId, String meetingDate, int startMinute,
                            int endMinute, String purpose, Status status, Boolean reviewEligible, boolean reviewWritten) {
        if (matchId == null || matchId.isBlank() || startMinute < 0 || startMinute >= endMinute || endMinute > 1440) throw new IllegalArgumentException("Invalid appointment");
        LocalDate.parse(meetingDate);
        this.matchId = matchId; this.chatRoomId = chatRoomId; this.meetingDate = meetingDate;
        this.startMinute = startMinute; this.endMinute = endMinute;
        this.purpose = purpose == null ? "" : purpose; this.status = java.util.Objects.requireNonNull(status);
        this.reviewEligible = reviewEligible; this.reviewWritten = reviewWritten;
    }
    public long startAtMillis() { return midnight() + startMinute * 60_000L; }
    public long endAtMillis() { return midnight() + endMinute * 60_000L; }
    private long midnight() { return LocalDate.parse(meetingDate).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(); }
}
