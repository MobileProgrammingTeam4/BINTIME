package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.R;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.MockAsyncTimetableRepository;
import com.team4.bintime.data.repository.MockTimetableRepositoryImpl;
import com.team4.bintime.data.repository.TimetableFailure;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ── [BT-026 후속, B 제안 ①] 읽기 실패 문구 구분과 [다시 확인] ──────────────────

    /** 화면이 받은 호출을 순서대로 기록한다. */
    private static final class RecordingScreen implements TimetableReplaceCheck.Screen {
        final List<String> calls = new ArrayList<>();
        int saves;

        @Override
        public void saveWithoutConfirm() {
            saves++;
            calls.add("save");
        }

        @Override
        public void confirmWithCount(int count) {
            calls.add("count:" + count);
        }

        @Override
        public void confirmUnknown() {
            calls.add("unknown");
        }
    }

    @Test
    public void unknownPrompt_usesDifferentTitleAndMessage_andOffersRetry() {
        TimetableReplaceCheck.Kind known = TimetableReplaceCheck.Kind.CONFIRM_WITH_COUNT;
        TimetableReplaceCheck.Kind unknown = TimetableReplaceCheck.Kind.CONFIRM_UNKNOWN;
        assertEquals(R.string.timetable_replace_title, TimetableReplaceCheck.titleRes(known));
        assertEquals(R.string.timetable_replace_message, TimetableReplaceCheck.messageRes(known));
        assertEquals(R.string.timetable_replace_unknown_title, TimetableReplaceCheck.titleRes(unknown));
        assertEquals(R.string.timetable_replace_message_unknown, TimetableReplaceCheck.messageRes(unknown));
        assertNotEquals(TimetableReplaceCheck.titleRes(known), TimetableReplaceCheck.titleRes(unknown));
        assertNotEquals(TimetableReplaceCheck.messageRes(known), TimetableReplaceCheck.messageRes(unknown));
        assertTrue(TimetableReplaceCheck.canRetry(unknown));
        assertFalse(TimetableReplaceCheck.canRetry(known));
        assertFalse(TimetableReplaceCheck.canRetry(TimetableReplaceCheck.Kind.SAVE));
    }

    @Test
    public void unknownText_saysNotChecked_andHasNoCount() throws IOException {
        String knownTitle = string("timetable_replace_title");
        String knownMessage = string("timetable_replace_message");
        String unknownTitle = string("timetable_replace_unknown_title");
        String unknownMessage = string("timetable_replace_message_unknown");
        // 수를 아는 문구는 수를 넣고, 실패 문구는 수를 말하지 않으며 확인하지 못했다고 먼저 말한다.
        assertTrue(knownMessage.contains("%1$d"));
        assertFalse(unknownMessage.contains("%"));
        assertFalse(unknownMessage.contains("지금 등록된 수업"));
        assertTrue(unknownTitle.contains("확인하지 못했"));
        assertNotEquals(knownTitle, unknownTitle);
        assertNotEquals(knownMessage, unknownMessage);
        assertFalse(string("timetable_replace_retry").isEmpty());
    }

    @Test
    public void readFails_showsUnknown_andNeverSaves() {
        uid = null;
        RecordingScreen screen = new RecordingScreen();
        TimetableReplaceCheck.check(repo, screen);
        assertEquals(Collections.singletonList("unknown"), screen.calls);
        assertEquals(0, screen.saves);
    }

    @Test
    public void retryFails_showsSameUnknownAgain_andNeverSaves() {
        uid = null;
        RecordingScreen screen = new RecordingScreen();
        TimetableReplaceCheck.check(repo, screen);
        TimetableReplaceCheck.check(repo, screen); // [다시 확인]
        TimetableReplaceCheck.check(repo, screen); // [다시 확인]
        assertEquals(Arrays.asList("unknown", "unknown", "unknown"), screen.calls);
        assertEquals(0, screen.saves);
    }

    @Test
    public void retrySucceeds_withCourses_showsCountPrompt_notSave() {
        store(Arrays.asList(course("a", 1, 540, 600), course("b", 2, 540, 600)));
        String signedIn = uid;
        uid = null;
        RecordingScreen screen = new RecordingScreen();
        TimetableReplaceCheck.check(repo, screen);
        uid = signedIn; // 다시 읽기 성공
        TimetableReplaceCheck.check(repo, screen);
        assertEquals(Arrays.asList("unknown", "count:2"), screen.calls);
        assertEquals(0, screen.saves);
    }

    @Test
    public void retrySucceeds_withNoCourses_savesWithoutConfirm() {
        store(Collections.emptyList());
        String signedIn = uid;
        uid = null;
        RecordingScreen screen = new RecordingScreen();
        TimetableReplaceCheck.check(repo, screen);
        assertEquals(0, screen.saves);
        uid = signedIn;
        TimetableReplaceCheck.check(repo, screen);
        assertEquals(Arrays.asList("unknown", "save"), screen.calls);
        assertEquals(1, screen.saves);
    }

    @Test
    public void route_everyFailureCode_neverSaves() {
        for (TimetableFailure.Code code : TimetableFailure.Code.values()) {
            RecordingScreen screen = new RecordingScreen();
            TimetableReplaceCheck.route(TimetableReplaceCheck.decide(0, TimetableFailure.of(code, "x")), screen);
            assertEquals(code.name(), Collections.singletonList("unknown"), screen.calls);
            assertEquals(code.name(), 0, screen.saves);
        }
    }

    /** strings_timetable.xml 의 실제 문구. 유닛 테스트 작업 폴더는 app 모듈이다. */
    private static String string(String name) throws IOException {
        File file = new File("src/main/res/values/strings_timetable.xml");
        if (!file.exists()) file = new File("app/src/main/res/values/strings_timetable.xml");
        String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<string name=\"" + Pattern.quote(name) + "\">(.*?)</string>").matcher(xml);
        assertTrue(name, m.find());
        return m.group(1);
    }
}
