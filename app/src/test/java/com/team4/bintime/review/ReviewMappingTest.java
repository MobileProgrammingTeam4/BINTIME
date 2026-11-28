package com.team4.bintime.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BT-044 getMatchReview 응답 매핑·실패 분류·진입 판단·요청 데이터. */
public class ReviewMappingTest {

    private static final String MATCH = "e".repeat(64);

    private static Map<String, Object> view(String satisfaction, List<?> keywords, Object comment) {
        Map<String, Object> v = new HashMap<>();
        v.put("satisfaction", satisfaction);
        v.put("keywords", keywords);
        v.put("comment", comment);
        v.put("createdAt", 1_700_000_000_000L);
        return v;
    }

    private static Map<String, Object> response(boolean canWrite, Object mine, String visibility, Object received) {
        Map<String, Object> r = new HashMap<>();
        r.put("matchId", MATCH);
        r.put("partnerUid", "u1");
        r.put("canWrite", canWrite);
        r.put("meetingDate", "2026-11-27");
        r.put("startMinute", 600);
        r.put("endMinute", 690);
        r.put("mine", mine);
        Map<String, Object> rec = new HashMap<>();
        rec.put("visibility", visibility);
        rec.put("review", received);
        r.put("received", rec);
        return r;
    }

    @Test
    public void parse_visible_mapsBothReviewsAndSnapshot() {
        MatchReview r = ReviewMapper.parse(response(false, view("GREAT", Arrays.asList("KIND_MANNER", "ON_TIME"), "또 만나요"),
                "VISIBLE", view("GOOD", Collections.singletonList("CAMPUS_TIPS"), null)), "u0");
        assertEquals("u1", r.partnerUid);
        assertFalse(r.canWrite);
        assertEquals(600, r.startMinute);
        assertEquals(Satisfaction.GREAT, r.mine.satisfaction);
        assertEquals(Arrays.asList(ReviewKeyword.ON_TIME, ReviewKeyword.KIND_MANNER), r.mine.keywords);
        assertEquals("u0", r.mine.authorUid);
        assertEquals("u1", r.received.authorUid);
        assertNull(r.received.comment);
        assertEquals(1_700_000_000_000L, r.received.createdAt);
    }

    @Test
    public void parse_locked_neverExposesReceived_evenIfSent() {
        MatchReview r = ReviewMapper.parse(response(true, null, "LOCKED", view("BAD", Collections.emptyList(), "x")), "u0");
        assertEquals(ReviewRepository.Visibility.LOCKED, r.visibility);
        assertNull(r.received);
        assertFalse(r.myReviewWritten());
        assertTrue(r.canWrite);
    }

    @Test
    public void parse_rejectsBrokenShapes_andDropsUnknownKeywords() {
        assertNull(ReviewMapper.parse(null, "u0"));
        assertNull(ReviewMapper.parse(response(true, null, "SOMETHING", null), "u0"));
        assertNull(ReviewMapper.parse(response(true, null, "VISIBLE", null), "u0"));
        Map<String, Object> noPartner = response(true, null, "LOCKED", null);
        noPartner.remove("partnerUid");
        assertNull(ReviewMapper.parse(noPartner, "u0"));
        MatchReview r = ReviewMapper.parse(response(false, view("GOOD", Arrays.asList("NEW_ONE", "ON_TIME"), " "), "NOT_YET", null), "u0");
        assertEquals(Collections.singletonList(ReviewKeyword.ON_TIME), r.mine.keywords);
        assertNull(r.mine.comment);
    }

    @Test
    public void failure_fromServerReasonAndPlatformCode() {
        Map<String, Object> details = new HashMap<>();
        details.put("reason", "ALREADY_REVIEWED");
        assertEquals(ReviewFailure.ALREADY_REVIEWED, ReviewFailure.from("FAILED_PRECONDITION", details));
        details.put("reason", "NOT_ELIGIBLE");
        assertEquals(ReviewFailure.NOT_ELIGIBLE, ReviewFailure.from("FAILED_PRECONDITION", details));
        details.put("reason", "PARTNER_DELETED");
        assertEquals(ReviewFailure.PARTNER_DELETED, ReviewFailure.from("FAILED_PRECONDITION", details));
        details.put("reason", "PERMISSION_DENIED");
        assertEquals(ReviewFailure.PERMISSION, ReviewFailure.from("PERMISSION_DENIED", details));
        details.put("reason", "RETRY_LATER");
        assertEquals(ReviewFailure.RETRY, ReviewFailure.from("UNAVAILABLE", details));
        details.put("reason", "INVALID_INPUT");
        assertEquals(ReviewFailure.GENERIC, ReviewFailure.from("INVALID_ARGUMENT", details));
        assertEquals(ReviewFailure.RETRY, ReviewFailure.from(null, null));
        assertEquals(ReviewFailure.PERMISSION, ReviewFailure.from("UNAUTHENTICATED", null));
        assertEquals(ReviewFailure.RETRY, ReviewFailure.from("DEADLINE_EXCEEDED", null));
    }

    @Test
    public void entry_opensDetailIfWritten_writeIfAllowed_otherwiseNothing() {
        Review mine = ReviewMapper.parse(response(false, view("GOOD", Collections.emptyList(), null), "NOT_YET", null), "u0").mine;
        assertEquals(ReviewEntry.Destination.DETAIL, ReviewEntry.decide(
                new MatchReview(MATCH, "u1", false, null, -1, -1, mine, ReviewRepository.Visibility.NOT_YET, null)));
        assertEquals(ReviewEntry.Destination.WRITE, ReviewEntry.decide(
                new MatchReview(MATCH, "u1", true, null, -1, -1, null, ReviewRepository.Visibility.LOCKED, null)));
        // reviewEligible=false(시작 전 종료·ACCOUNT_DELETED)·상대 탈퇴는 서버가 canWrite=false 로 준다.
        assertEquals(ReviewEntry.Destination.NOT_AVAILABLE, ReviewEntry.decide(
                new MatchReview(MATCH, "u1", false, null, -1, -1, null, ReviewRepository.Visibility.LOCKED, null)));
    }

    @Test
    public void entry_timeLabel_fromSnapshot() {
        assertEquals("11월 27일 (금) 10:00 ~ 11:30", ReviewEntry.timeLabel("2026-11-27", 600, 690));
        assertEquals("11월 27일 (금) 23:00 ~ 24:00", ReviewEntry.timeLabel("2026-11-27", 1380, 1440));
        assertNull(ReviewEntry.timeLabel(null, 600, 690));
        assertNull(ReviewEntry.timeLabel("2026-11-27", -1, -1));
        assertNull(ReviewEntry.timeLabel("2026-11-27", 690, 600));
        assertNull(ReviewEntry.timeLabel("bad", 600, 690));
    }

    @Test
    public void submitData_sendsEnumNamesInOrder_andNullForBlankComment() {
        Map<String, Object> data = FunctionsReviewRepository.submitData(MATCH, Satisfaction.GOOD,
                EnumSet.of(ReviewKeyword.CAMPUS_TIPS, ReviewKeyword.ON_TIME), "   ");
        assertEquals(MATCH, data.get("matchId"));
        assertEquals("GOOD", data.get("satisfaction"));
        assertEquals(Arrays.asList("ON_TIME", "CAMPUS_TIPS"), data.get("keywords"));
        assertNull(data.get("comment"));
        assertTrue(data.containsKey("comment"));
        assertFalse("상대 UID 는 보내지 않는다", data.containsKey("targetUid"));
        assertEquals(" 좋았어요 ".trim(), FunctionsReviewRepository.submitData(MATCH, Satisfaction.GREAT, null, " 좋았어요 ").get("comment"));
    }
}
