package com.team4.bintime.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.availability.Availability;
import com.team4.bintime.data.availability.FreeTimeCalculator;
import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * [BT-056] 시간표 저장 경로의 경계값 회귀 테스트. BT-016·BT-036·BT-047 테스트에 없는 경우만 둔다:
 * 공강이 하나도 없는 경우(빈 선택·30분만·수업 없는 날만), 하루 전체 수업, 정각이 아닌 수업 시작의 칸 경계,
 * 저장된 availability 로 본 최소 겹침 필터 경계.
 */
@SuppressWarnings("unchecked")
public class TimetableBoundaryTest {

    private static final int MON = 1;
    private static final int TUE = 2;
    private static final int[] FILTERS = {60, 90, 120, 150, 180};

    private MockAsyncTimetableRepository repo;

    @Before
    public void setUp() {
        MockTimetableRepositoryImpl.getInstance().resetToMockData();
        repo = new MockAsyncTimetableRepository(MockTimetableRepositoryImpl.getInstance(), () -> "uid-bt056");
    }

    private static final class Result<T> implements AsyncTimetableRepository.Callback<T> {
        TimetableFailure failure;
        int calls;

        @Override
        public void onSuccess(T result) {
            calls++;
        }

        @Override
        public void onError(TimetableFailure f) {
            failure = f;
            calls++;
        }
    }

    private static Course course(String id, int day, int start, int end) {
        return new Course(id, "수업" + id, null, null, null,
                new ArrayList<>(Collections.singletonList(new TimeSlot(day, start, end))));
    }

    private static void ok(Result<?> r) {
        assertEquals(1, r.calls);
        assertNull("unexpected failure " + r.failure, r.failure);
    }

    private Result<Void> replace(Course... courses) {
        Result<Void> r = new Result<>();
        repo.replaceCourses(Arrays.asList(courses), r);
        return r;
    }

    private Result<Void> saveFree(TimeSlot... slots) {
        Result<Void> r = new Result<>();
        repo.saveFreeSlots(Arrays.asList(slots), r);
        return r;
    }

    /** 구독으로 받은 현재 선택 원본(붙은 칸은 합쳐서 복원). */
    private List<TimeSlot> selection() {
        final List<TimeSlot>[] last = new List[1];
        AsyncTimetableRepository.Registration reg = repo.observeTimetable(
                new AsyncTimetableRepository.Listener<com.team4.bintime.data.model.Timetable>() {
                    @Override
                    public void onData(com.team4.bintime.data.model.Timetable t) {
                        last[0] = t == null ? null : t.getFreeSlots();
                    }

                    @Override
                    public void onError(TimetableFailure f) {
                    }
                });
        reg.remove();
        assertNotNull(last[0]);
        return last[0];
    }

    private int revision() {
        return MockTimetableRepositoryImpl.getInstance().getTimetable("uid-bt056").getRevision();
    }

    // ── 공강 없음 ─────────────────────────────────────

    @Test
    public void emptySelection_afterSelection_clearsAvailability_andKeepsRevision() {
        ok(replace(course("a", MON, 600, 690)));
        ok(saveFree(new TimeSlot(MON, 720, 840)));
        int revision = revision();

        ok(saveFree());

        Availability a = repo.getAvailability("uid-bt056");
        assertNotNull(a);
        assertTrue(a.getSlots().isEmpty());
        assertEquals(revision, a.getSourceRevision());
        assertEquals(revision, revision());
    }

    @Test
    public void onlyThirtyMinuteCells_keepSelectionButNoMatchableSlot() {
        ok(replace(course("a", MON, 600, 690)));

        ok(saveFree(new TimeSlot(MON, 540, 570), new TimeSlot(MON, 720, 750), new TimeSlot(TUE, 600, 630)));

        assertEquals(3, selection().size());
        assertTrue(repo.getAvailability("uid-bt056").getSlots().isEmpty());
    }

    @Test
    public void wholeDayCourse_clearsThatDaysCells_otherDayKept() {
        ok(saveFree(new TimeSlot(MON, 540, 660), new TimeSlot(TUE, 540, 660)));

        ok(replace(course("all-day", MON, 0, 1440)));

        assertEquals(Collections.singletonList(new TimeSlot(TUE, 540, 660)), selection());
        Availability a = repo.getAvailability("uid-bt056");
        assertEquals(Collections.singletonList(new TimeSlot(TUE, 540, 660)), a.getSlots());
        assertEquals(revision(), a.getSourceRevision());
        // 하루 전체 수업과 겹치는 칸은 0분·1440분 끝 칸도 선택할 수 없다.
        assertEquals(TimetableFailure.Code.INVALID_FREE_SLOT, failureOf(saveFree(new TimeSlot(MON, 0, 30))));
        assertEquals(TimetableFailure.Code.INVALID_FREE_SLOT, failureOf(saveFree(new TimeSlot(MON, 1410, 1440))));
    }

    // ── 시각 경계 ─────────────────────────────────────

    @Test
    public void courseStartingOffGrid_blocksTheCellItStartsIn_previousCellStaysSelectable() {
        ok(replace(course("a", MON, 615, 690))); // 10:15~11:30

        assertEquals(TimetableFailure.Code.INVALID_FREE_SLOT, failureOf(saveFree(new TimeSlot(MON, 600, 630))));
        ok(saveFree(new TimeSlot(MON, 540, 600), new TimeSlot(MON, 690, 750)));
        assertEquals(Arrays.asList(new TimeSlot(MON, 540, 600), new TimeSlot(MON, 690, 750)),
                repo.getAvailability("uid-bt056").getSlots());
    }

    // ── 최소 겹침 필터 경계(저장된 availability 기준) ─────────────

    @Test
    public void savedAvailability_meetsEachFilterExactly_andMissesByThirty() {
        for (int min : FILTERS) {
            MockTimetableRepositoryImpl.getInstance().resetToMockData();
            ok(replace());
            ok(saveFree(new TimeSlot(MON, 540, 540 + min)));
            List<TimeSlot> mine = repo.getAvailability("uid-bt056").getSlots();

            List<TimeSlot> exact = Collections.singletonList(new TimeSlot(MON, 540, 540 + min));
            List<TimeSlot> shorter = Collections.singletonList(new TimeSlot(MON, 570, 540 + min));
            assertEquals("filter " + min, 1, FreeTimeCalculator.matchableOverlaps(mine, exact, min).size());
            assertTrue("filter " + min, FreeTimeCalculator.matchableOverlaps(mine, shorter, min).isEmpty());
        }
    }

    private static TimetableFailure.Code failureOf(Result<?> r) {
        assertEquals(1, r.calls);
        assertNotNull(r.failure);
        return r.failure.getCode();
    }
}
