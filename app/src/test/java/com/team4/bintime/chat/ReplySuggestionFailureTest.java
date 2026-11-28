package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.team4.bintime.request.MatchAppointment;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BT-041: BT-024 오류 표 → 화면 처리, 응답 검사, 보낼 약속 선택. */
public class ReplySuggestionFailureTest {

    private static Map<String, Object> details(String reason, boolean retryable) {
        Map<String, Object> map = new HashMap<>();
        map.put("reason", reason);
        map.put("retryable", retryable);
        return map;
    }

    @Test
    public void serverReasons_followBt024Table() {
        assertEquals(ReplySuggestionFailure.SIGNED_OUT, ReplySuggestionFailure.from("UNAUTHENTICATED", details("UNAUTHENTICATED", false)));
        assertEquals(ReplySuggestionFailure.PERMISSION, ReplySuggestionFailure.from("PERMISSION_DENIED", details("PERMISSION_DENIED", false)));
        assertEquals(ReplySuggestionFailure.QUOTA, ReplySuggestionFailure.from("RESOURCE_EXHAUSTED", details("QUOTA_EXCEEDED", false)));
        assertEquals(ReplySuggestionFailure.RETRY, ReplySuggestionFailure.from("RESOURCE_EXHAUSTED", details("AI_RATE_LIMITED", true)));
        assertEquals(ReplySuggestionFailure.RETRY, ReplySuggestionFailure.from("DEADLINE_EXCEEDED", details("AI_TIMEOUT", true)));
        assertEquals(ReplySuggestionFailure.RETRY, ReplySuggestionFailure.from("INTERNAL", details("AI_INVALID_OUTPUT", true)));
        assertEquals(ReplySuggestionFailure.BLOCKED, ReplySuggestionFailure.from("FAILED_PRECONDITION", details("AI_BLOCKED", false)));
        assertEquals(ReplySuggestionFailure.RETRY, ReplySuggestionFailure.from("UNAVAILABLE", details("AI_UNAVAILABLE", true)));
        assertEquals(ReplySuggestionFailure.UNAVAILABLE, ReplySuggestionFailure.from("UNAVAILABLE", details("AI_UNAVAILABLE", false)));
        assertEquals(ReplySuggestionFailure.UNAVAILABLE, ReplySuggestionFailure.from("UNIMPLEMENTED", details("AI_NOT_IMPLEMENTED", false)));
        assertEquals(ReplySuggestionFailure.GENERIC, ReplySuggestionFailure.from("INVALID_ARGUMENT", details("INVALID_INPUT", false)));
        assertEquals(ReplySuggestionFailure.GENERIC, ReplySuggestionFailure.from("INTERNAL", details("INTERNAL", false)));
        assertEquals(ReplySuggestionFailure.GENERIC, ReplySuggestionFailure.from("INTERNAL", details("SOMETHING_NEW", true)));
    }

    @Test
    public void withoutDetails_usesPlatformCode_andNetworkIsRetry() {
        assertEquals(ReplySuggestionFailure.RETRY, ReplySuggestionFailure.from(null, null));
        assertEquals(ReplySuggestionFailure.RETRY, ReplySuggestionFailure.from("UNAVAILABLE", null));
        assertEquals(ReplySuggestionFailure.RETRY, ReplySuggestionFailure.from("DEADLINE_EXCEEDED", "not a map"));
        assertEquals(ReplySuggestionFailure.SIGNED_OUT, ReplySuggestionFailure.from("UNAUTHENTICATED", null));
        assertEquals(ReplySuggestionFailure.PERMISSION, ReplySuggestionFailure.from("PERMISSION_DENIED", null));
        assertEquals(ReplySuggestionFailure.GENERIC, ReplySuggestionFailure.from("NOT_FOUND", null));
    }

    private static Map<String, Object> response(Object replies) {
        Map<String, Object> data = new HashMap<>();
        data.put("replies", replies);
        Map<String, Object> body = new HashMap<>();
        body.put("data", data);
        return body;
    }

    @Test
    public void parseReplies_acceptsOnlyThreeDistinctNonBlank() {
        assertEquals(Arrays.asList("좋아요", "몇 시요?", "어디서요?"),
                FunctionsReplySuggestionRepository.parseReplies(response(Arrays.asList(" 좋아요 ", "몇 시요?", "어디서요?"))));
        assertNull(FunctionsReplySuggestionRepository.parseReplies(response(Arrays.asList("a", "b"))));
        assertNull(FunctionsReplySuggestionRepository.parseReplies(response(Arrays.asList("a", "a", "b"))));
        assertNull(FunctionsReplySuggestionRepository.parseReplies(response(Arrays.asList("a", " ", "b"))));
        assertNull(FunctionsReplySuggestionRepository.parseReplies(response(Arrays.asList("a", 3, "b"))));
        assertNull(FunctionsReplySuggestionRepository.parseReplies(response("abc")));
        assertNull(FunctionsReplySuggestionRepository.parseReplies(null));
        assertNull(FunctionsReplySuggestionRepository.parseReplies(Collections.singletonMap("data", null)));
    }

    private static final String DAY = "2026-11-24";
    private static final long NOON = LocalDate.parse(DAY).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() + 12 * 3_600_000L;

    private static MatchAppointment appointment(String id, int start, int end, MatchAppointment.Status status) {
        return new MatchAppointment(id, "room-1", DAY, start, end, "스터디", status, null, false);
    }

    @Test
    public void targetMatchId_prefersRepresentative_thenLatestEnded_thenRoomKey() {
        List<MatchAppointment> mixed = Arrays.asList(
                appointment("ended-early", 540, 600, MatchAppointment.Status.ENDED),
                appointment("active-now", 690, 780, MatchAppointment.Status.ACTIVE));
        assertEquals("active-now", ReplySuggestionRepositories.targetMatchId(mixed, "room-1", NOON));

        List<MatchAppointment> allEnded = Arrays.asList(
                appointment("ended-early", 540, 600, MatchAppointment.Status.ENDED),
                appointment("ended-late", 610, 700, MatchAppointment.Status.ENDED));
        assertEquals("ended-late", ReplySuggestionRepositories.targetMatchId(allEnded, "room-1", NOON));

        assertEquals("room-1", ReplySuggestionRepositories.targetMatchId(Collections.emptyList(), "room-1", NOON));
    }
}
