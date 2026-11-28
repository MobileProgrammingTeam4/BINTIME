package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.request.MatchAppointment;
import com.team4.bintime.request.MatchAppointmentPolicy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** BT-044 #147 리뷰 2번: 서버 canWrite 를 채팅방 후기 버튼·선택 대상에 반영한다. */
public class ReviewTargetsTest {

    private static MatchAppointment ended(String id, Boolean eligible) {
        return new MatchAppointment(id, "room", "2026-11-27", 600, 690, "스터디", MatchAppointment.Status.ENDED, eligible, false);
    }

    private static List<String> targets(List<MatchAppointment> list) {
        return MatchAppointmentPolicy.targets(list, MatchAppointmentPolicy.Action.REVIEW).stream()
                .map(a -> a.matchId).collect(Collectors.toList());
    }

    private final List<MatchAppointment> two = Arrays.asList(ended("a", true), ended("b", true));

    @Test
    public void beforeServerAnswer_keepsLocalValues() {
        List<MatchAppointment> result = ReviewTargets.apply(two, Collections.emptyMap());
        assertEquals(Arrays.asList("a", "b"), targets(result));
        assertFalse(MatchAppointmentPolicy.allReviewsWritten(result)); // '남기기'
    }

    @Test
    public void written_isView_canWrite_isWrite() {
        Map<String, ReviewTargets.State> server = new HashMap<>();
        server.put("a", new ReviewTargets.State(true, false));
        server.put("b", new ReviewTargets.State(false, true));
        List<MatchAppointment> result = ReviewTargets.apply(two, server);
        assertEquals(Arrays.asList("a", "b"), targets(result));
        assertFalse("하나라도 안 썼으면 '남기기'", MatchAppointmentPolicy.allReviewsWritten(result));

        server.put("b", new ReviewTargets.State(true, false));
        assertTrue("전부 썼으면 '보기'", MatchAppointmentPolicy.allReviewsWritten(ReviewTargets.apply(two, server)));
    }

    @Test
    public void notWrittenAndCannotWrite_isExcluded_evenIfLocalSaysEligible() {
        // 정상 종료(reviewEligible=true) 뒤 상대가 탈퇴하면 서버는 canWrite=false 를 준다.
        Map<String, ReviewTargets.State> server = new HashMap<>();
        server.put("a", new ReviewTargets.State(true, false));
        server.put("b", new ReviewTargets.State(false, false));
        List<MatchAppointment> result = ReviewTargets.apply(two, server);
        assertEquals(Collections.singletonList("a"), targets(result));
        assertTrue("남은 대상(a)은 썼으므로 '보기'", MatchAppointmentPolicy.allReviewsWritten(result));
    }

    @Test
    public void allExcluded_hidesButton() {
        Map<String, ReviewTargets.State> server = new HashMap<>();
        server.put("a", new ReviewTargets.State(false, false));
        server.put("b", new ReviewTargets.State(false, false));
        assertTrue(targets(ReviewTargets.apply(two, server)).isEmpty());
    }

    @Test
    public void serverNeverRestoresLocallyIneligible() {
        // 시작 전 종료(reviewEligible=false)는 서버가 canWrite 를 줄 수 없고, 와도 로컬 false 를 유지한다.
        Map<String, ReviewTargets.State> server = new HashMap<>();
        server.put("a", new ReviewTargets.State(false, true));
        assertTrue(targets(ReviewTargets.apply(Collections.singletonList(ended("a", false)), server)).isEmpty());
    }
}
