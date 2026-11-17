package com.team4.bintime.request;

import org.junit.Test;
import java.time.Instant;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MatchAppointmentPolicyTest {
    private final long now = Instant.parse("2026-11-17T04:30:00Z").toEpochMilli(); // KST 13:30
    private MatchAppointment item(String id, String day, int start, int end, MatchAppointment.Status status, Boolean review) {
        return new MatchAppointment(id, "room", day, start, end, "스터디", status, review, false);
    }
    @Test public void ongoingWinsThenNearestFutureAndOnlyUnfinishedCounts() {
        MatchAppointment ongoing = item("current", "2026-11-17", 780, 840, MatchAppointment.Status.ACTIVE, null);
        MatchAppointment future = item("future", "2026-11-18", 60, 120, MatchAppointment.Status.ACTIVE, null);
        MatchAppointment old = item("old", "2026-11-16", 780, 840, MatchAppointment.Status.ENDED, true);
        assertSame(ongoing, MatchAppointmentPolicy.representative(Arrays.asList(future, old, ongoing), now));
        assertEquals(1, MatchAppointmentPolicy.additionalCount(Arrays.asList(future, old, ongoing), now));
        assertSame(future, MatchAppointmentPolicy.representative(Arrays.asList(future, ongoing), ongoing.endAtMillis()));
    }
    @Test public void futureEndAllowedButEarlyEndNeverBecomesReviewEligible() {
        MatchAppointment future = item("future", "2026-11-18", 600, 690, MatchAppointment.Status.ACTIVE, null);
        MatchAppointment earlyEnded = item("early", "2026-11-18", 600, 690, MatchAppointment.Status.ENDED, false);
        MatchAppointment legacyUnknown = item("unknown", "2026-11-16", 600, 690, MatchAppointment.Status.ENDED, null);
        MatchAppointment completed = item("ended", "2026-11-16", 600, 690, MatchAppointment.Status.ENDED, true);
        assertEquals(1, MatchAppointmentPolicy.targets(Arrays.asList(future, earlyEnded, completed), MatchAppointmentPolicy.Action.END).size());
        assertEquals(Arrays.asList(completed), MatchAppointmentPolicy.targets(Arrays.asList(earlyEnded, legacyUnknown, completed), MatchAppointmentPolicy.Action.REVIEW));
        assertNull(MatchAppointmentPolicy.representative(Arrays.asList(earlyEnded, legacyUnknown, completed), future.endAtMillis()));
    }
    @Test public void midnightEnd1440UsesNextSeoulDay() {
        MatchAppointment midnight = item("night", "2026-11-17", 1380, 1440, MatchAppointment.Status.ACTIVE, null);
        assertEquals(Instant.parse("2026-11-17T15:00:00Z").toEpochMilli(), midnight.endAtMillis());
        assertNull(MatchAppointmentPolicy.representative(Arrays.asList(midnight), midnight.endAtMillis()));
    }
    @Test public void reviewButtonStaysWriteUntilEveryEligibleAppointmentIsWritten() {
        MatchAppointment first = new MatchAppointment("a", "room", "2026-11-16", 600, 690, "스터디", MatchAppointment.Status.ENDED, true, true);
        MatchAppointment second = new MatchAppointment("b", "room", "2026-11-16", 720, 810, "스터디", MatchAppointment.Status.ENDED, true, false);
        assertFalse(MatchAppointmentPolicy.allReviewsWritten(Arrays.asList(first, second)));
        MatchAppointment completed = new MatchAppointment("b", "room", "2026-11-16", 720, 810, "스터디", MatchAppointment.Status.ENDED, true, true);
        assertTrue(MatchAppointmentPolicy.allReviewsWritten(Arrays.asList(first, completed)));
    }
    @Test public void earlyEndedAndUnknownEligibilityDoNotKeepWrittenButtonInWriteState() {
        MatchAppointment written = new MatchAppointment("a", "room", "2026-11-16", 600, 690, "스터디", MatchAppointment.Status.ENDED, true, true);
        MatchAppointment early = item("early", "2026-11-16", 720, 810, MatchAppointment.Status.ENDED, false);
        MatchAppointment unknown = item("unknown", "2026-11-16", 840, 930, MatchAppointment.Status.ENDED, null);
        assertTrue(MatchAppointmentPolicy.allReviewsWritten(Arrays.asList(written, early, unknown)));
        assertFalse(MatchAppointmentPolicy.allReviewsWritten(Arrays.asList(early, unknown)));
    }
}
