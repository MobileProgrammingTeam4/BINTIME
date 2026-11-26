package com.team4.bintime.data.repository;

import com.team4.bintime.data.availability.Availability;
import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-015] MockAsyncTimetableRepository — 콜백·구독·계정 분리·겹침 거부.
 */
public class MockAsyncTimetableRepositoryTest {

    private static final int MON = 1;
    private static final int TUE = 2;

    private String currentUid;
    private MockAsyncTimetableRepository repo;

    @Before
    public void setUp() {
        MockTimetableRepositoryImpl.getInstance().resetToMockData();
        currentUid = "uid-a";
        repo = new MockAsyncTimetableRepository(MockTimetableRepositoryImpl.getInstance(), () -> currentUid);
    }

    // ── 헬퍼 ─────────────────────────────────────────

    private static Course course(String id, int day, int start, int end) {
        return new Course(id, "수업" + id, null, null, null,
                new ArrayList<>(Collections.singletonList(new TimeSlot(day, start, end))));
    }

    private static final class Result<T> implements AsyncTimetableRepository.Callback<T> {
        T value;
        TimetableFailure failure;
        int calls;

        @Override
        public void onSuccess(T result) {
            value = result;
            calls++;
        }

        @Override
        public void onError(TimetableFailure f) {
            failure = f;
            calls++;
        }
    }

    private static final class Recorder implements AsyncTimetableRepository.Listener<Timetable> {
        final List<Timetable> data = new ArrayList<>();
        final List<TimetableFailure> errors = new ArrayList<>();

        @Override
        public void onData(Timetable t) {
            data.add(t);
        }

        @Override
        public void onError(TimetableFailure f) {
            errors.add(f);
        }

        Timetable last() {
            return data.get(data.size() - 1);
        }
    }

    private <T> Result<T> ok(Result<T> r) {
        assertEquals(1, r.calls);
        assertNull("unexpected failure " + r.failure, r.failure);
        return r;
    }

    private static void assertFailure(TimetableFailure.Code code, Result<?> r) {
        assertEquals(1, r.calls);
        assertNotNull(r.failure);
        assertEquals(code, r.failure.getCode());
    }

    private Result<Course> add(Course c) {
        Result<Course> r = new Result<>();
        repo.addCourse(c, r);
        return r;
    }

    // ── 구독 ─────────────────────────────────────────

    @Test
    public void observe_unregistered_deliversNull() {
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertEquals(1, rec.data.size());
        assertNull(rec.data.get(0));
    }

    @Test
    public void observe_receivesUpdatesAfterAddAndDelete() {
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        ok(add(course("a", MON, 600, 690)));
        assertEquals(2, rec.data.size());
        assertEquals(1, rec.last().getCourseCount());

        Result<Void> del = new Result<>();
        repo.deleteCourse("a", del);
        ok(del);
        assertEquals(3, rec.data.size());
        assertEquals(0, rec.last().getCourseCount());
    }

    @Test
    public void observe_removeStopsUpdates_andIsIdempotent() {
        Recorder rec = new Recorder();
        AsyncTimetableRepository.Registration reg = repo.observeTimetable(rec);
        reg.remove();
        reg.remove();
        ok(add(course("a", MON, 600, 690)));
        assertEquals(1, rec.data.size());
    }

    @Test
    public void observe_notSignedIn_error() {
        currentUid = null;
        Recorder rec = new Recorder();
        AsyncTimetableRepository.Registration reg = repo.observeTimetable(rec);
        assertTrue(rec.data.isEmpty());
        assertEquals(TimetableFailure.Code.NOT_SIGNED_IN, rec.errors.get(0).getCode());
        reg.remove();
    }

    @Test
    public void observe_snapshotIsCopy() {
        ok(add(course("a", MON, 600, 690)));
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        Timetable snap = rec.last();
        snap.getCourses().get(0).getTimeSlots().get(0).setEndMinute(1000);
        snap.getCourses().clear();

        Recorder again = new Recorder();
        repo.observeTimetable(again);
        assertEquals(1, again.last().getCourseCount());
        assertEquals(690, again.last().getCourses().get(0).getTimeSlots().get(0).getEndMinute());
        assertNotSame(snap, again.last());
    }

    // ── 계정 분리 ─────────────────────────────────────

    @Test
    public void accounts_separated() {
        Recorder recA = new Recorder();
        repo.observeTimetable(recA);

        currentUid = "uid-b";
        Recorder recB = new Recorder();
        repo.observeTimetable(recB);
        ok(add(course("b1", MON, 600, 690)));

        assertEquals(1, recA.data.size());   // A는 B의 쓰기 알림을 받지 않는다
        assertEquals(1, recB.last().getCourseCount());

        currentUid = "uid-a";
        ok(add(course("a1", MON, 600, 690)));  // B와 같은 시간이어도 A에서는 겹침이 아니다
        assertEquals(1, recA.last().getCourseCount());
        assertEquals("a1", recA.last().getCourses().get(0).getCourseId());
        assertEquals(1, recB.last().getCourseCount());
    }

    // ── 추가 ─────────────────────────────────────────

    @Test
    public void add_returnsSanitizedCopy() {
        Course c = new Course("a", "  자료구조 ", " ", null, null,
                new ArrayList<>(Collections.singletonList(new TimeSlot(MON, 600, 690))));
        Result<Course> r = ok(add(c));
        assertEquals("자료구조", r.value.getName());
        assertNull(r.value.getProfessor());
        assertNotSame(c, r.value);
    }

    @Test
    public void add_overlap_rejectedAndNotSaved() {
        ok(add(course("a", MON, 600, 690)));
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        Result<Course> r = add(course("b", MON, 660, 720));
        assertFailure(TimetableFailure.Code.OVERLAP, r);
        assertEquals("a", r.failure.getConflictingCourseId());
        assertEquals(1, rec.data.size());
        assertEquals(1, rec.last().getCourseCount());
    }

    @Test
    public void add_touching_allowed() {
        ok(add(course("a", MON, 600, 660)));
        ok(add(course("b", MON, 660, 720)));
    }

    @Test
    public void add_duplicateId_invalid() {
        ok(add(course("a", MON, 600, 660)));
        assertFailure(TimetableFailure.Code.INVALID_COURSE, add(course("a", TUE, 600, 660)));
    }

    @Test
    public void add_invalidOrNull_invalid() {
        assertFailure(TimetableFailure.Code.INVALID_COURSE, add(null));
        assertFailure(TimetableFailure.Code.INVALID_COURSE, add(course("a", MON, 660, 600)));
    }

    @Test
    public void add_notSignedIn() {
        currentUid = null;
        assertFailure(TimetableFailure.Code.NOT_SIGNED_IN, add(course("a", MON, 600, 660)));
    }

    @Test
    public void add_incrementsRevision() {
        ok(add(course("a", MON, 600, 660)));
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        int before = rec.last().getRevision();
        ok(add(course("b", TUE, 600, 660)));
        assertEquals(before + 1, rec.last().getRevision());
    }

    @Test
    public void nullCallback_allowed() {
        repo.addCourse(course("a", MON, 600, 660), null);
        repo.addCourse(course("b", MON, 630, 700), null);
        repo.deleteCourse("a", null);
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertEquals(0, rec.last().getCourseCount());
    }

    // ── 수정 ─────────────────────────────────────────

    @Test
    public void update_moveWithinOwnTime_allowed() {
        ok(add(course("a", MON, 600, 690)));
        Result<Course> r = new Result<>();
        repo.updateCourse(course("a", MON, 630, 720), r);
        ok(r);
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertEquals(720, rec.last().getCourses().get(0).getTimeSlots().get(0).getEndMinute());
    }

    @Test
    public void update_overlapWithOther_rejected() {
        ok(add(course("a", MON, 600, 690)));
        ok(add(course("b", MON, 780, 840)));
        Result<Course> r = new Result<>();
        repo.updateCourse(course("a", MON, 630, 800), r);
        assertFailure(TimetableFailure.Code.OVERLAP, r);
        assertEquals("b", r.failure.getConflictingCourseId());
    }

    @Test
    public void update_missing_notFound() {
        Result<Course> r = new Result<>();
        repo.updateCourse(course("zz", MON, 600, 660), r);
        assertFailure(TimetableFailure.Code.NOT_FOUND, r);
    }

    // ── 삭제 ─────────────────────────────────────────

    @Test
    public void delete_missing_idempotentSuccess_noNotify() {
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        Result<Void> r = new Result<>();
        repo.deleteCourse("none", r);
        ok(r);
        assertEquals(1, rec.data.size());
    }

    @Test
    public void delete_emptyId_invalid() {
        Result<Void> r = new Result<>();
        repo.deleteCourse(" ", r);
        assertFailure(TimetableFailure.Code.INVALID_COURSE, r);
    }

    @Test
    public void delete_notSignedIn() {
        currentUid = null;
        Result<Void> r = new Result<>();
        repo.deleteCourse("a", r);
        assertFailure(TimetableFailure.Code.NOT_SIGNED_IN, r);
    }

    // ── 일괄 교체 ─────────────────────────────────────

    @Test
    public void replace_newTimetable_revision1() {
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        Result<Void> r = new Result<>();
        repo.replaceCourses(Arrays.asList(course("a", MON, 600, 660), course("b", MON, 660, 720)), r);
        ok(r);
        assertEquals(2, rec.last().getCourseCount());
        assertEquals(1, rec.last().getRevision());
    }

    @Test
    public void replace_existing_replacesAllAndBumpsRevision() {
        ok(add(course("old", MON, 600, 660)));
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        int before = rec.last().getRevision();
        Result<Void> r = new Result<>();
        repo.replaceCourses(Collections.singletonList(course("new", MON, 600, 660)), r);
        ok(r);
        assertEquals(1, rec.last().getCourseCount());
        assertEquals("new", rec.last().getCourses().get(0).getCourseId());
        assertEquals(before + 1, rec.last().getRevision());
    }

    @Test
    public void replace_overlapInBatch_rejectedAndUnchanged() {
        ok(add(course("old", MON, 600, 660)));
        Result<Void> r = new Result<>();
        repo.replaceCourses(Arrays.asList(course("a", MON, 600, 690), course("b", MON, 660, 720)), r);
        assertFailure(TimetableFailure.Code.OVERLAP, r);
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertEquals("old", rec.last().getCourses().get(0).getCourseId());
    }

    @Test
    public void replace_null_invalid_emptyAllowed() {
        Result<Void> bad = new Result<>();
        repo.replaceCourses(null, bad);
        assertFailure(TimetableFailure.Code.INVALID_COURSE, bad);

        ok(add(course("a", MON, 600, 660)));
        Result<Void> empty = new Result<>();
        repo.replaceCourses(Collections.emptyList(), empty);
        ok(empty);
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertEquals(0, rec.last().getCourseCount());
    }

    // ── BT-016 공강 선택·availability ─────────────────

    private Result<Void> saveFree(TimeSlot... slots) {
        Result<Void> r = new Result<>();
        repo.saveFreeSlots(Arrays.asList(slots), r);
        return r;
    }

    private Result<Void> replace(Course... courses) {
        Result<Void> r = new Result<>();
        repo.replaceCourses(Arrays.asList(courses), r);
        return r;
    }

    @Test
    public void saveFreeSlots_storesSelection_andMatchableAvailability() {
        ok(replace(course("a", MON, 600, 690)));
        int revision = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision();
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);

        // 30분 단독 + 붙은 두 칸(60분) + 화 90분
        ok(saveFree(new TimeSlot(MON, 540, 570), new TimeSlot(MON, 720, 750), new TimeSlot(MON, 750, 780),
                new TimeSlot(TUE, 600, 690)));

        // 선택 원본: 30분 단독 포함, 붙은 칸은 합쳐서 복원
        assertEquals(Arrays.asList(new TimeSlot(MON, 540, 570), new TimeSlot(MON, 720, 780),
                new TimeSlot(TUE, 600, 690)), rec.last().getFreeSlots());
        // 매칭 대상: 60분 이상만, sourceRevision = 현재 revision (공강 저장은 revision을 올리지 않음)
        Availability a = repo.getAvailability("uid-a");
        assertEquals(Arrays.asList(new TimeSlot(MON, 720, 780), new TimeSlot(TUE, 600, 690)), a.getSlots());
        assertEquals(revision, a.getSourceRevision());
        assertEquals(revision, rec.last().getRevision());
    }

    @Test
    public void saveFreeSlots_overlappingCourse_rejectedAndNotSaved() {
        ok(replace(course("a", MON, 600, 690)));
        assertFailure(TimetableFailure.Code.INVALID_FREE_SLOT, saveFree(new TimeSlot(MON, 660, 720)));
        assertTrue(repo.getAvailability("uid-a").getSlots().isEmpty());
    }

    @Test
    public void saveFreeSlots_offGrid_rejected() {
        assertFailure(TimetableFailure.Code.INVALID_FREE_SLOT, saveFree(new TimeSlot(MON, 545, 600)));
        assertNull(repo.getAvailability("uid-a"));
    }

    @Test
    public void saveFreeSlots_over40Blocks_rejectedBeforeSaving() {
        List<TimeSlot> slots = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            for (int start = 0; start + 60 <= 1440 && slots.size() < Availability.MAX_SLOTS + 1; start += 90) {
                slots.add(new TimeSlot(day, start, start + 60));
            }
        }
        assertEquals(Availability.MAX_SLOTS + 1, slots.size());
        Result<Void> r = new Result<>();
        repo.saveFreeSlots(slots, r);
        assertFailure(TimetableFailure.Code.TOO_MANY_FREE_SLOTS, r);
        assertNull(repo.getAvailability("uid-a"));
    }

    @Test
    public void saveFreeSlots_withoutTimetable_createsRevision1() {
        ok(saveFree(new TimeSlot(MON, 600, 660)));
        assertEquals(1, MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision());
        assertEquals(1, repo.getAvailability("uid-a").getSourceRevision());
    }

    @Test
    public void replaceCourses_clearsOverlappingCells_andRefreshesAvailability() {
        ok(replace(course("a", MON, 540, 600)));
        ok(saveFree(new TimeSlot(MON, 600, 780))); // 10:00~13:00
        ok(replace(course("a", MON, 540, 600), course("b", MON, 660, 690))); // 11:00~11:30 수업 추가
        int revision = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision();

        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertEquals(Arrays.asList(new TimeSlot(MON, 600, 660), new TimeSlot(MON, 690, 780)),
                rec.last().getFreeSlots());
        Availability a = repo.getAvailability("uid-a");
        assertEquals(Arrays.asList(new TimeSlot(MON, 600, 660), new TimeSlot(MON, 690, 780)), a.getSlots());
        assertEquals(revision, a.getSourceRevision());
    }

    @Test
    public void courseChangeWithoutReplace_leavesAvailabilityStale() {
        ok(saveFree(new TimeSlot(MON, 600, 720)));
        ok(add(course("a", TUE, 600, 690)));
        int revision = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision();
        assertTrue(repo.getAvailability("uid-a").getSourceRevision() < revision);
    }

    @Test
    public void freeSlots_separatedByAccount() {
        ok(saveFree(new TimeSlot(MON, 600, 720)));
        currentUid = "uid-b";
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertNull(rec.last());
        assertNull(repo.getAvailability("uid-b"));
        ok(saveFree(new TimeSlot(TUE, 600, 660)));
        assertEquals(Collections.singletonList(new TimeSlot(TUE, 600, 660)),
                repo.getAvailability("uid-b").getSlots());
        assertEquals(Collections.singletonList(new TimeSlot(MON, 600, 720)),
                repo.getAvailability("uid-a").getSlots());
    }

    @Test
    public void saveFreeSlots_notSignedIn() {
        currentUid = null;
        assertFailure(TimetableFailure.Code.NOT_SIGNED_IN, saveFree(new TimeSlot(MON, 600, 660)));
    }

    // ── [BT-036] 수업 변경과 공강 정리 경계 ─────────────

    private static final int THU = 4;
    private static final int SUN = 7;

    @Test
    public void replaceCourses_splittingBlockOver40_rejectedAndUnchanged() {
        // 현재 동작 고정(docs/BT-016.md 한계): 수업이 긴 공강 블록을 둘로 쪼개 매칭 대상이 40 → 41개가 되면
        // 수업 교체 자체가 TOO_MANY_FREE_SLOTS로 거부된다. 상한 40은 Rules·앱 검증(요청 생성 서버의 availability 재검증 포함)과 묶인
        // 팀 결정이다. 검색 서버(searchMatches)는 slots를 최대 200개까지 읽고 40개 검사는 하지 않는다.
        List<TimeSlot> free = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            for (int start = 0; start + 60 <= 1440 && free.size() < Availability.MAX_SLOTS - 1; start += 90) {
                free.add(new TimeSlot(day, start, start + 60));
            }
        }
        free.add(new TimeSlot(THU, 600, 780)); // 목 10:00~13:00 한 블록
        assertEquals(Availability.MAX_SLOTS, free.size());
        Result<Void> saved = new Result<>();
        repo.saveFreeSlots(free, saved);
        ok(saved);
        int revision = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision();

        // 목 11:00~12:00 수업 → 10:00~11:00 / 12:00~13:00 두 블록으로 쪼개져 41개
        assertFailure(TimetableFailure.Code.TOO_MANY_FREE_SLOTS, replace(course("a", THU, 660, 720)));
        Timetable after = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a");
        assertEquals(revision, after.getRevision());
        assertEquals(0, after.getCourseCount());
        assertEquals(Availability.MAX_SLOTS, repo.getAvailability("uid-a").getSlots().size());

        // 쪼개진 한쪽이 60분 미만이면 41개가 되지 않아 허용된다: 11:30~12:30 → 10:00~11:30 / 12:30~13:00(30분)
        ok(replace(course("a", THU, 690, 750)));
        assertEquals(Availability.MAX_SLOTS, repo.getAvailability("uid-a").getSlots().size());
    }

    @Test
    public void addCourse_keepsOverlappingCells_resavingThemIsRejected() {
        // 수업 개별 추가는 freeCells를 정리하지 않는다(replaceCourses만 정리). 주말 칸도 같다.
        ok(saveFree(new TimeSlot(SUN, 600, 720)));
        ok(add(course("a", SUN, 630, 690)));
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertEquals(Collections.singletonList(new TimeSlot(SUN, 600, 720)), rec.last().getFreeSlots());
        // 겹친 칸을 지우지 않고 그대로 다시 보내면 거부된다
        assertFailure(TimetableFailure.Code.INVALID_FREE_SLOT, saveFree(new TimeSlot(SUN, 600, 720)));
        // 겹친 칸을 지운 선택은 저장된다
        ok(saveFree(new TimeSlot(SUN, 600, 630), new TimeSlot(SUN, 690, 720)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_nullArgs_throw() {
        new MockAsyncTimetableRepository(null, () -> "x");
    }

    // ── [BT-047] 시간표 변경은 새 검색 데이터(availability)만 갱신 ─────────────

    @Test
    public void replaceCourses_removingCourse_doesNotReselectFreedCells_andRefreshesAvailability() {
        ok(replace(course("a", MON, 600, 660), course("b", MON, 720, 780)));
        ok(saveFree(new TimeSlot(MON, 540, 600), new TimeSlot(MON, 660, 720)));

        ok(replace(course("b", MON, 720, 780))); // 10:00~11:00 수업 삭제 → 그 칸은 비지만 선택되지 않는다
        int revision = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision();

        List<TimeSlot> expected = Arrays.asList(new TimeSlot(MON, 540, 600), new TimeSlot(MON, 660, 720));
        Recorder rec = new Recorder();
        repo.observeTimetable(rec);
        assertEquals(expected, rec.last().getFreeSlots());
        Availability a = repo.getAvailability("uid-a");
        assertEquals(expected, a.getSlots()); // 09:00~12:00으로 합쳐지지 않는다
        assertEquals(revision, a.getSourceRevision());
    }

    @Test
    public void replaceThenSaveFreeSlots_availabilityFollowsLatestSelectionAndRevision() {
        ok(replace(course("a", MON, 600, 690)));
        ok(saveFree(new TimeSlot(MON, 690, 810))); // 11:30~13:30
        int before = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision();

        // 12:00~12:30 수업 추가 → 11:30~12:00(30분, 원본에만 남음) / 12:30~13:30(60분, 매칭 대상)
        ok(replace(course("a", MON, 600, 690), course("c", MON, 720, 750)));
        int afterReplace = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision();
        assertEquals(before + 1, afterReplace);
        Availability replaced = repo.getAvailability("uid-a");
        assertEquals(Collections.singletonList(new TimeSlot(MON, 750, 810)), replaced.getSlots());
        assertEquals(afterReplace, replaced.getSourceRevision());

        // 공강만 다시 고르면 revision은 그대로, availability는 새 선택으로 전부 바뀐다
        ok(saveFree(new TimeSlot(MON, 780, 900)));
        assertEquals(afterReplace, MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision());
        Availability saved = repo.getAvailability("uid-a");
        assertEquals(Collections.singletonList(new TimeSlot(MON, 780, 900)), saved.getSlots());
        assertEquals(afterReplace, saved.getSourceRevision());
    }

    @Test
    public void replaceCourses_multiSlotCourse_clearsCellsOnEveryDay() {
        Course multi = new Course("m", "수업m", null, null, null, new ArrayList<>(Arrays.asList(
                new TimeSlot(MON, 600, 660), new TimeSlot(TUE, 600, 660))));
        ok(saveFree(new TimeSlot(MON, 540, 720), new TimeSlot(TUE, 540, 720)));

        ok(replace(multi));
        int revision = MockTimetableRepositoryImpl.getInstance().getTimetable("uid-a").getRevision();
        List<TimeSlot> expected = Arrays.asList(new TimeSlot(MON, 540, 600), new TimeSlot(MON, 660, 720),
                new TimeSlot(TUE, 540, 600), new TimeSlot(TUE, 660, 720));
        Availability a = repo.getAvailability("uid-a");
        assertEquals(expected, a.getSlots());
        assertEquals(revision, a.getSourceRevision());
    }
}
