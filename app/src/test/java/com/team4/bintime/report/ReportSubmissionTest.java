package com.team4.bintime.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.profile.MockBlockRepository;
import com.team4.bintime.profile.MockProfileRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** BT-051 신고 입력 형식(서버 계약)과 Mock 중복·함께 차단 정책. */
public class ReportSubmissionTest {

    private MockBlockRepository blocks;
    private MockReportRepository repository;

    @Before
    public void setUp() {
        blocks = new MockBlockRepository(new MockProfileRepository());
        repository = new MockReportRepository(blocks);
    }

    private ReportRepository.Result submit(ReportForm form) {
        ReportRepository.Result[] result = {null};
        repository.submit(form, new ReportRepository.Callback() {
            @Override public void onSuccess(ReportRepository.Result r) { result[0] = r; }
            @Override public void onError(String message) { }
        });
        return result[0];
    }

    @Test
    public void callableDataMatchesServerContractWithoutReporterUid() {
        ReportForm form = new ReportForm("u1", "a".repeat(64),
                EnumSet.of(ReportReason.SPAM, ReportReason.NO_SHOW), "  노쇼였어요  ", true);
        Map<String, Object> data = FirebaseReportRepository.toData(form);
        assertEquals(Arrays.asList("targetUid", "matchId", "reasons", "detail", "alsoBlock").stream().sorted().toList(),
                data.keySet().stream().sorted().toList()); // 신고자 UID 는 보내지 않는다
        assertEquals("u1", data.get("targetUid"));
        assertEquals("노쇼였어요", data.get("detail"));
        assertEquals(Boolean.TRUE, data.get("alsoBlock"));
        @SuppressWarnings("unchecked") List<String> reasons = (List<String>) data.get("reasons");
        assertEquals(Arrays.asList("NO_SHOW", "SPAM"), reasons); // 서버 REPORT_REASONS 와 같은 enum 이름

        Map<String, Object> outside = FirebaseReportRepository.toData(
                new ReportForm("u1", null, EnumSet.of(ReportReason.SPAM), "   ", false));
        assertTrue(outside.containsKey("matchId"));
        assertNull(outside.get("matchId")); // 매칭 밖 신고는 null 을 명시해서 보낸다(서버 nullable)
        assertNull(outside.get("detail"));
    }

    @Test
    public void sameMatchIsReportedOnceAndAlsoBlockBlocks() {
        ReportForm form = new ReportForm("u1", "m1", EnumSet.of(ReportReason.NO_SHOW), null, true);
        ReportRepository.Result first = submit(form);
        assertNotNull(first);
        assertFalse(first.alreadyReported);
        assertTrue(first.blocked);
        assertTrue(blocks.isBlocked("u1"));

        ReportRepository.Result again = submit(form);
        assertTrue(again.alreadyReported);
        assertFalse(submit(new ReportForm("u1", null, EnumSet.of(ReportReason.SPAM), null, false)).alreadyReported);
    }

    @Test
    public void withoutAlsoBlockDoesNotBlock() {
        ReportRepository.Result result = submit(new ReportForm("u2", "m1", EnumSet.of(ReportReason.SPAM), null, false));
        assertFalse(result.blocked);
        assertFalse(blocks.isBlocked("u2"));
    }

    @Test
    public void invalidFormIsRejected() {
        assertNull(submit(new ReportForm("u1", "m1", EnumSet.noneOf(ReportReason.class), null, false)));
        assertNull(submit(new ReportForm("u1", "m1", EnumSet.of(ReportReason.SPAM),
                "가".repeat(ReportForm.DETAIL_MAX_LENGTH + 1), false)));
    }

    @Test
    public void submitErrorIsShownOnce() {
        ReportSubmitViewModel.State failed = new ReportSubmitViewModel.State(ReportSubmitViewModel.Status.FAILED, null, "실패");
        assertTrue(failed.consumeError());
        assertFalse(failed.consumeError()); // 회전 후 다시 관찰돼도 토스트를 두 번 띄우지 않는다
        assertFalse(new ReportSubmitViewModel.State(ReportSubmitViewModel.Status.DONE, null, null).consumeError());
    }
}
