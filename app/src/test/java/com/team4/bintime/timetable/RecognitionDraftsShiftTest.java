package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.recognition.RecognitionReview;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** [BT-048] 검수 화면 시작·종료 ±30분 빠른 조정. */
public class RecognitionDraftsShiftTest {

    private static final int STEP = RecognitionDrafts.SHIFT_MINUTES;

    private static TimetableDraft draft(Course... rows) {
        return new TimetableDraft(new ArrayList<>(Arrays.asList(rows)));
    }

    private static Course row(String day, String start, String end) {
        Course c = new Course("수업", "가상관 101호", day, start, end);
        c.markRecognized(0.95, 1);
        return c;
    }

    @Test
    public void shiftStartAndEnd_byThirtyMinutes() {
        TimetableDraft d = draft(row("월", "13:00", "14:30"));

        assertTrue(RecognitionDrafts.shift(d, 0, true, STEP));
        assertEquals("13:30", d.courses.get(0).getStartTime());
        assertTrue(RecognitionDrafts.shift(d, 0, false, -STEP));
        assertEquals("14:00", d.courses.get(0).getEndTime());
        assertTrue(RecognitionDrafts.shift(d, 0, false, STEP));
        assertEquals("14:30", d.courses.get(0).getEndTime());
    }

    @Test
    public void midnightBoundaries_zeroAndFourteenForty() {
        TimetableDraft d = draft(row("월", "00:00", "01:00"), row("화", "23:00", "24:00"));

        assertFalse(RecognitionDrafts.canShift(d, 0, true, -STEP));
        assertFalse(RecognitionDrafts.shift(d, 0, true, -STEP));
        assertEquals("00:00", d.courses.get(0).getStartTime());

        assertFalse(RecognitionDrafts.canShift(d, 1, false, STEP));
        assertTrue(RecognitionDrafts.shift(d, 1, true, STEP));
        assertEquals("23:30", d.courses.get(1).getStartTime());

        TimetableDraft late = draft(row("수", "23:00", "23:30"));
        assertTrue(RecognitionDrafts.shift(late, 0, false, STEP));
        assertEquals("24:00", late.courses.get(0).getEndTime());
        assertEquals(1440, RecognitionDrafts.minutes(late.courses.get(0).getEndTime()));
    }

    @Test
    public void endNeverMovesToOrBeforeStart() {
        TimetableDraft d = draft(row("월", "10:00", "10:30"));

        assertFalse(RecognitionDrafts.shift(d, 0, false, -STEP));
        assertFalse(RecognitionDrafts.shift(d, 0, true, STEP));
        assertEquals("10:00", d.courses.get(0).getStartTime());
        assertEquals("10:30", d.courses.get(0).getEndTime());
    }

    @Test
    public void unreadableTimeOrBadIndex_isNotShifted() {
        TimetableDraft d = draft(row("월", "??", "10:30"));

        assertFalse(RecognitionDrafts.canShift(d, 0, false, STEP));
        assertFalse(RecognitionDrafts.shift(d, 1, true, STEP));
        assertFalse(RecognitionDrafts.shift(d, -1, true, STEP));
        assertFalse(RecognitionDrafts.shift(null, 0, true, STEP));
    }

    @Test
    public void flagsAreRecomputedAfterShift_conflictAppearsAndClears() {
        TimetableDraft d = draft(row("월", "09:00", "10:00"), row("월", "10:00", "11:00"));
        assertTrue(RecognitionReview.canConfirm(RecognitionDrafts.check(d)));

        assertTrue(RecognitionDrafts.shift(d, 0, false, STEP));
        List<Set<RecognitionReview.Flag>> flags = RecognitionDrafts.check(d);
        assertTrue(flags.get(0).contains(RecognitionReview.Flag.CONFLICT));
        assertFalse(RecognitionReview.canConfirm(flags));

        assertTrue(RecognitionDrafts.shift(d, 0, false, -STEP));
        assertTrue(RecognitionReview.canConfirm(RecognitionDrafts.check(d)));
    }

    @Test
    public void shiftKeepsRecognitionInfoAndOtherRows() {
        TimetableDraft d = draft(row("월", "09:00", "10:00"), row("화", "11:00", "12:00"));
        Course before = d.courses.get(1);

        RecognitionDrafts.shift(d, 0, true, STEP);

        assertEquals(0.95, d.courses.get(0).getRecognitionConfidence(), 0.0);
        assertEquals("11:00", before.getStartTime());
        assertEquals(2, d.courses.size());
    }
}
