package com.team4.bintime.chat;

import com.team4.bintime.request.MatchAppointment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 채팅방 후기 버튼·선택 창에 넘길 약속 목록(BT-044, #147 리뷰 2번).
 * 서버(getMatchReview) 조회가 끝난 약속은 서버 판단을 따른다:
 * 내 후기가 있으면 작성함, 아직 안 썼고 canWrite=false(매칭 뒤 상대 탈퇴 등)면 reviewEligible=false 로 바꿔
 * MatchAppointmentPolicy(D)가 후기 대상에서 빼게 한다. 조회 전 약속은 로컬 값을 그대로 쓰고, 제출할 때 서버가 다시 검사한다.
 * Firebase 에 의존하지 않아 JVM 단위 테스트로 검증한다.
 */
final class ReviewTargets {

    /** 서버가 알려 준 약속 하나의 후기 상태 */
    static final class State {
        final boolean written, canWrite;

        State(boolean written, boolean canWrite) {
            this.written = written;
            this.canWrite = canWrite;
        }
    }

    private ReviewTargets() {}

    static List<MatchAppointment> apply(List<MatchAppointment> all, Map<String, State> server) {
        List<MatchAppointment> result = new ArrayList<>();
        for (MatchAppointment a : all) {
            State state = server.get(a.matchId);
            Boolean eligible = state == null || state.written || state.canWrite ? a.reviewEligible : Boolean.FALSE;
            boolean written = state == null ? a.reviewWritten : state.written;
            result.add(new MatchAppointment(a.matchId, a.chatRoomId, a.meetingDate, a.startMinute, a.endMinute,
                    a.purpose, a.status, eligible, written));
        }
        return result;
    }
}
