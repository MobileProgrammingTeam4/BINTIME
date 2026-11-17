package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.MockAsyncTimetableRepository;
import com.team4.bintime.data.repository.MockTimetableRepositoryImpl;
import com.team4.bintime.data.repository.TimetableFailure;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * [BT-026 결정 0-3] 교체 확인 판단: 서버 수업 수만 쓴다. 0개면 확인 없이 저장, 있으면 수와 함께 확인,
 * 서버 값을 못 얻으면(오프라인·권한·로그아웃 등) 수를 뺀 확인.
 */
public class TimetableReplaceCheckTest {

    private String uid;
    private MockAsyncTimetableRepository repo;

    @Before
    public void setUp() {
        MockTimetableRepositoryImpl.getInstance().resetToMockData();
        uid = "uid-replace-check";
        repo = new MockAsyncTimetableRepository(MockTimetableRepositoryImpl.getInstance(), () -> uid);
    }

    private static Course course(String id, int day, int start, int end) {
        return new Course(id, "수업" + id, null, null, null,
                new ArrayList<>(Collections.singletonList(new TimeSlot(day, start, end))));
    }

    private void store(List<Course> courses) {
        TimetableFailure[] error = {null};
        repo.replaceCourses(courses, new AsyncTimetableRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
            }

            @Override
            public void onError(TimetableFailure failure) {
                error[0] = failure;
            }
        });
        assertNull(error[0]);
    }

    private TimetableReplaceCheck.Decision run() {
        TimetableReplaceCheck.Decision[] got = {null};
        TimetableReplaceCheck.run(repo, d -> got[0] = d);
        assertNotNull("Mock 은 동기로 답한다", got[0]);
        return got[0];
    }

    @Test
    public void serverHasNoCourses_saveWithoutConfirm() {
        store(Collections.emptyList());
        assertEquals(TimetableReplaceCheck.Kind.SAVE, run().kind);
    }

    @Test
    public void serverHasCourses_confirmWithServerCount() {
        store(Arrays.asList(course("a", 1, 540, 600), course("b", 2, 540, 600), course("c", 3, 540, 600)));
        TimetableReplaceCheck.Decision d = run();
        assertEquals(TimetableReplaceCheck.Kind.CONFIRM_WITH_COUNT, d.kind);
        assertEquals(3, d.existingCount);
    }

    @Test
    public void serverReadFails_confirmWithoutCount() {
        uid = null; // 로그아웃: 서버 값을 얻지 못한 경우의 대표
        assertEquals(TimetableReplaceCheck.Kind.CONFIRM_UNKNOWN, run().kind);
    }

    @Test
    public void decide_failureNeverMeansSave() {
        // 오프라인(Source.SERVER 실패)·권한 거부 등 어떤 실패도 "0개 → 바로 저장"이 되지 않는다.
        for (TimetableFailure.Code code : TimetableFailure.Code.values()) {
            TimetableReplaceCheck.Decision d = TimetableReplaceCheck.decide(0, TimetableFailure.of(code, "x"));
            assertEquals(code.name(), TimetableReplaceCheck.Kind.CONFIRM_UNKNOWN, d.kind);
        }
        assertEquals(TimetableReplaceCheck.Kind.CONFIRM_UNKNOWN, TimetableReplaceCheck.decide(null, null).kind);
        assertEquals(TimetableReplaceCheck.Kind.CONFIRM_UNKNOWN, TimetableReplaceCheck.decide(-1, null).kind);
        assertEquals(TimetableReplaceCheck.Kind.SAVE, TimetableReplaceCheck.decide(0, null).kind);
        assertEquals(7, TimetableReplaceCheck.decide(7, null).existingCount);
    }

    @Test
    public void countFollowsLatestWrites() {
        store(Collections.singletonList(course("a", 1, 540, 600)));
        assertEquals(1, run().existingCount);
        store(Collections.emptyList());
        assertEquals(TimetableReplaceCheck.Kind.SAVE, run().kind);
    }
}
