package com.team4.bintime.request;

import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.util.TimeUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** 실제 API는 candidateId만 받아 서버의 BT-017 적격 후보를 해석한다. 시간 입력은 Mock 전용이다. */
public final class RequestDraft {
    public final String candidateId, clientRequestId, toUid, purpose, meetingDate;
    public final int startMinute, endMinute;
    public final boolean mock;

    private RequestDraft(String candidateId, String clientRequestId, String toUid, String purpose,
                         String date, int start, int end, boolean mock) {
        this.candidateId = candidateId;
        this.clientRequestId = clientRequestId;
        this.toUid = toUid;
        this.purpose = purpose;
        meetingDate = date;
        startMinute = start;
        endMinute = end;
        this.mock = mock;
    }

    /** BT-017 연결점. candidateId는 서버가 발급하고 검증할 불투명 식별자다. */
    public static RequestDraft verified(String candidateId, String toUid, String clientRequestId) {
        return new RequestDraft(candidateId, clientRequestId, toUid, null, null, 0, 0, false);
    }

    public static RequestDraft mock(MatchCandidate candidate) {
        return mock(candidate.uid, candidate.purpose, LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),
                TimeUtils.parseMinutes(candidate.overlapStart), TimeUtils.parseMinutes(candidate.overlapEnd));
    }

    public static RequestDraft mock(String toUid, String purpose, String date, int start, int end) {
        return new RequestDraft("mock:" + toUid, UUID.randomUUID().toString(), toUid, purpose,
                date, start, end, true);
    }
}
