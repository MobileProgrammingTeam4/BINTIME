package com.team4.bintime.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;

public class ReportFormTest {

    @Test
    public void validForm_trimsDetailAndKeepsContext() {
        ReportForm form = new ReportForm("u1", "match-jimin", EnumSet.of(ReportReason.NO_SHOW, ReportReason.SPAM),
                "  약속 시간에 오지 않았어요  ", true);
        assertNull(form.validate());
        assertEquals("약속 시간에 오지 않았어요", form.detail);
        assertEquals("match-jimin", form.matchId);
        assertTrue(form.alsoBlock);
        assertEquals(2, form.reasons.size());
    }

    @Test
    public void blankDetail_becomesNull_andMatchIdIsOptional() {
        ReportForm form = new ReportForm("u1", null, EnumSet.of(ReportReason.IMPERSONATION), "   ", false);
        assertNull(form.validate());
        assertNull(form.detail);
        assertNull(form.matchId);
    }

    @Test
    public void rejectsMissingTargetNoReasonAndLongDetail() {
        assertEquals(ReportForm.Problem.MISSING_TARGET,
                new ReportForm(" ", null, EnumSet.of(ReportReason.SPAM), null, false).validate());
        assertEquals(ReportForm.Problem.NO_REASON,
                new ReportForm("u1", null, EnumSet.noneOf(ReportReason.class), null, false).validate());
        assertEquals(ReportForm.Problem.NO_REASON, new ReportForm("u1", null, null, null, false).validate());
        char[] tooLong = new char[ReportForm.DETAIL_MAX_LENGTH + 1];
        Arrays.fill(tooLong, '가');
        assertEquals(ReportForm.Problem.DETAIL_TOO_LONG,
                new ReportForm("u1", null, EnumSet.of(ReportReason.SPAM), new String(tooLong), false).validate());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void reasons_areImmutable() {
        new ReportForm("u1", null, EnumSet.of(ReportReason.SPAM), null, false).reasons.add(ReportReason.NO_SHOW);
    }
}
