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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // ── [BT-026 후속, B 제안 ②] 저장 전 인터넷 확인 ──────────────────────────────

    /** 바꿔 끼우는 연결 상태. */
    private static final class FakeNetwork implements TimetableReplaceCheck.Network {
        boolean online;

        FakeNetwork(boolean online) {
            this.online = online;
        }

        @Override
        public boolean isOnline() {
            return online;
        }
    }

    /** 실제 Mock 저장소에 넘기며 메서드별 호출 수를 센다(서버 읽기·저장 0회 확인용). */
    private final Map<String, Integer> repoCalls = new HashMap<>();

    private AsyncTimetableRepository counting() {
        return (AsyncTimetableRepository) Proxy.newProxyInstance(
                AsyncTimetableRepository.class.getClassLoader(),
                new Class<?>[]{AsyncTimetableRepository.class},
                (proxy, method, args) -> {
                    repoCalls.merge(method.getName(), 1, Integer::sum);
                    return method.invoke(repo, args);
                });
    }

    private int calls(String name) {
        Integer n = repoCalls.get(name);
        return n == null ? 0 : n;
    }

    private int saveCalls() {
        return calls("replaceCourses") + calls("saveFreeSlots");
    }

    /** 화면처럼 동작한다: 저장 시작이면 실제로 수업 교체·공강 저장을 부른다. */
    private static final class SavingStart implements TimetableReplaceCheck.Start {
        final AsyncTimetableRepository repository;
        final List<String> calls = new ArrayList<>();

        SavingStart(AsyncTimetableRepository repository) {
            this.repository = repository;
        }

        private void doSave() {
            AsyncTimetableRepository.Callback<Void> ignore = new AsyncTimetableRepository.Callback<Void>() {
                @Override
                public void onSuccess(Void result) {
                }

                @Override
                public void onError(TimetableFailure failure) {
                }
            };
            repository.replaceCourses(Collections.singletonList(course("new", 1, 600, 660)), ignore);
            repository.saveFreeSlots(Collections.emptyList(), ignore);
        }

        @Override
        public void offline() {
            calls.add("offline");
        }

        @Override
        public void proceed() {
            calls.add("proceed");
            doSave();
        }

        @Override
        public void saveWithoutConfirm() {
            calls.add("save");
            doSave();
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
    public void offline_noServerRead_noSave_onlyNotice() {
        store(Arrays.asList(course("a", 1, 540, 600), course("b", 2, 540, 600)));
        AsyncTimetableRepository repository = counting();
        SavingStart start = new SavingStart(repository);
        FakeNetwork network = new FakeNetwork(false);
        // 교체 확인이 필요한 새 시간표, 확인이 필요 없는 수정, 연타 모두 서버 읽기·저장 0회.
        TimetableReplaceCheck.begin(network, true, repository, start);
        TimetableReplaceCheck.begin(network, false, repository, start);
        TimetableReplaceCheck.begin(network, true, repository, start);
        assertEquals(Arrays.asList("offline", "offline", "offline"), start.calls);
        assertEquals(0, calls("countCoursesFromServer"));
        assertEquals(0, saveCalls());
        assertTrue("다른 저장소 메서드도 부르지 않는다", repoCalls.isEmpty());
        TimetableReplaceCheck.Decision after = run(); // 기존 수업 그대로
        assertEquals(TimetableReplaceCheck.Kind.CONFIRM_WITH_COUNT, after.kind);
        assertEquals(2, after.existingCount);
    }

    @Test
    public void online_needsCheck_keepsServerCountPrompt() {
        store(Arrays.asList(course("a", 1, 540, 600), course("b", 2, 540, 600), course("c", 3, 540, 600)));
        AsyncTimetableRepository repository = counting();
        SavingStart start = new SavingStart(repository);
        TimetableReplaceCheck.begin(new FakeNetwork(true), true, repository, start);
        assertEquals(Collections.singletonList("count:3"), start.calls);
        assertEquals(1, calls("countCoursesFromServer"));
        assertEquals(0, saveCalls());
    }

    @Test
    public void online_noExistingCourses_savesWithoutConfirm() {
        store(Collections.emptyList());
        AsyncTimetableRepository repository = counting();
        SavingStart start = new SavingStart(repository);
        TimetableReplaceCheck.begin(new FakeNetwork(true), true, repository, start);
        assertEquals(Collections.singletonList("save"), start.calls);
        assertEquals(1, calls("countCoursesFromServer"));
        assertEquals(1, calls("replaceCourses"));
    }

    @Test
    public void online_noCheckNeeded_proceedsWithoutServerRead() {
        AsyncTimetableRepository repository = counting();
        SavingStart start = new SavingStart(repository);
        TimetableReplaceCheck.begin(new FakeNetwork(true), false, repository, start);
        assertEquals(Collections.singletonList("proceed"), start.calls);
        assertEquals(0, calls("countCoursesFromServer"));
        assertEquals(1, calls("replaceCourses"));
    }

    @Test
    public void online_readFails_stillShowsUnknown_andNeverSaves() {
        uid = null; // 온라인인데 서버 읽기 실패
        AsyncTimetableRepository repository = counting();
        SavingStart start = new SavingStart(repository);
        TimetableReplaceCheck.begin(new FakeNetwork(true), true, repository, start);
        assertEquals(Collections.singletonList("unknown"), start.calls);
        assertEquals(1, calls("countCoursesFromServer"));
        assertEquals(0, saveCalls());
    }

    @Test
    public void offlineThenOnline_pressAgain_proceedsNormally() {
        store(Arrays.asList(course("a", 1, 540, 600), course("b", 2, 540, 600)));
        AsyncTimetableRepository repository = counting();
        SavingStart start = new SavingStart(repository);
        FakeNetwork network = new FakeNetwork(false);
        TimetableReplaceCheck.begin(network, true, repository, start);
        assertEquals(0, calls("countCoursesFromServer"));
        network.online = true; // 연결 복구 후 다시 "다음"
        TimetableReplaceCheck.begin(network, true, repository, start);
        assertEquals(Arrays.asList("offline", "count:2"), start.calls);
        assertEquals(1, calls("countCoursesFromServer"));
        assertEquals(0, saveCalls());
    }

    @Test
    public void offlineThenOnline_noExistingCourses_savesOnSecondPress() {
        store(Collections.emptyList());
        AsyncTimetableRepository repository = counting();
        SavingStart start = new SavingStart(repository);
        FakeNetwork network = new FakeNetwork(false);
        TimetableReplaceCheck.begin(network, true, repository, start);
        assertEquals(0, saveCalls());
        network.online = true;
        TimetableReplaceCheck.begin(network, true, repository, start);
        assertEquals(Arrays.asList("offline", "save"), start.calls);
        assertEquals(1, calls("replaceCourses"));
        assertEquals(1, calls("saveFreeSlots"));
    }

    @Test
    public void offlineText_matchesProfileTone() throws IOException {
        assertEquals(string("profile_save_offline", "strings_profile.xml"), string("timetable_save_offline"));
    }

    /** strings_timetable.xml 의 실제 문구. 유닛 테스트 작업 폴더는 app 모듈이다. */
    private static String string(String name) throws IOException {
        return string(name, "strings_timetable.xml");
    }

    private static String string(String name, String fileName) throws IOException {
        File file = new File("src/main/res/values/" + fileName);
        if (!file.exists()) file = new File("app/src/main/res/values/" + fileName);
        String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<string name=\"" + Pattern.quote(name) + "\">(.*?)</string>").matcher(xml);
        assertTrue(name, m.find());
        return m.group(1);
    }
}
