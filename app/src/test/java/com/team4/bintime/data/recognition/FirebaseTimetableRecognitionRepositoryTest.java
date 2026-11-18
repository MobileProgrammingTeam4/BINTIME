package com.team4.bintime.data.recognition;

import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertTrue;


import com.team4.bintime.data.recognition.RecognitionFailure.Reason;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [BT-026 P4] analyzeTimetable Callable 저장소: 보내기 전 검사(서버 호출 0회), 입력 모양과 타임아웃, 감싼 응답 풀기,
 * 오류 변환, 콜백 1회·취소 뒤 무시. 실제 Firebase 를 부르지 않고 가짜 Caller 를 쓴다.
 */
public class FirebaseTimetableRecognitionRepositoryTest {

    private static final String ME = "uid-me";

    private String uid = ME;
    private final List<Map<String, Object>> sent = new ArrayList<>();
    private final List<Long> timeouts = new ArrayList<>();
    private final List<FirebaseTimetableRecognitionRepository.Listener> pending = new ArrayList<>();
    private final FirebaseTimetableRecognitionRepository repo = new FirebaseTimetableRecognitionRepository(
            () -> uid, (data, timeoutSeconds, listener) -> {
                sent.add(data);
                timeouts.add(timeoutSeconds);
                pending.add(listener);
            });

    private static List<String> paths(String uid, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add("timetableImages/" + uid + "/batch-1/" + i + ".jpg");
        return out;
    }

    private static final class Recorder implements TimetableRecognitionRepository.Callback {
        final List<RecognitionResult> results = new ArrayList<>();
        final List<RecognitionFailure> failures = new ArrayList<>();

        @Override
        public void onSuccess(RecognitionResult result) {
            results.add(result);
        }

        @Override
        public void onError(RecognitionFailure failure) {
            failures.add(failure);
        }

        int count() {
            return results.size() + failures.size();
        }
    }

    private static Map<String, Object> wrapped(int imageCount) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("task", "analyzeTimetable");
        envelope.put("data", MockTimetableRecognitionRepository.serverMock(imageCount));
        Map<String, Object> meta = new HashMap<>();
        meta.put("requestId", "req-1");
        envelope.put("meta", meta);
        return envelope;
    }

    @Test
    public void sendsOnlyImagePaths_inOrder_withTimeout130() {
        List<String> two = paths(ME, 2);
        repo.analyze(two, new Recorder());
        assertEquals(1, sent.size());
        assertEquals(Collections.singleton("imagePaths"), sent.get(0).keySet());
        assertEquals(two, sent.get(0).get("imagePaths"));
        assertEquals(Long.valueOf(130), timeouts.get(0));
        assertTrue(FirebaseTimetableRecognitionRepository.TIMEOUT_SECONDS > 120);
    }

    @Test
    public void invalidInput_failsWithoutCallingServer() {
        Object[][] cases = {
                {Collections.emptyList(), Reason.INVALID_INPUT},
                {paths(ME, 6), Reason.INVALID_INPUT},
                {paths("uid-other", 1), Reason.PERMISSION_DENIED},
                {Collections.singletonList("timetableImages/" + ME + "/../uid-other/b/0.jpg"), Reason.PERMISSION_DENIED},
        };
        for (Object[] c : cases) {
            Recorder r = new Recorder();
            @SuppressWarnings("unchecked") List<String> input = (List<String>) c[0];
            repo.analyze(input, r);
            assertEquals(c[1], r.failures.get(0).getReason());
        }
        uid = null;
        Recorder signedOut = new Recorder();
        repo.analyze(paths(ME, 1), signedOut);
        assertEquals(Reason.UNAUTHENTICATED, signedOut.failures.get(0).getReason());
        assertTrue(sent.isEmpty());
    }

    @Test
    public void wrappedResponse_isParsed() {
        Recorder r = new Recorder();
        repo.analyze(paths(ME, 2), r);
        pending.get(0).onSuccess(wrapped(2));
        assertEquals(1, r.results.size());
        assertEquals(4, r.results.get(0).courses.size());
        assertEquals(2, r.results.get(0).imageCount);
        assertEquals(Collections.singletonList("여러 이미지에서 같은 수업이 발견되었습니다."), r.results.get(0).warnings);
    }

    @Test
    public void unwrappedOrBrokenResponse_isInvalidOutput_retryable() {
        for (Object bad : new Object[]{MockTimetableRecognitionRepository.serverMock(1), null, "x",
                Collections.singletonMap("data", "x")}) {
            Recorder r = new Recorder();
            repo.analyze(paths(ME, 1), r);
            pending.get(pending.size() - 1).onSuccess(bad);
            assertEquals(Reason.AI_INVALID_OUTPUT, r.failures.get(0).getReason());
            assertTrue(r.failures.get(0).isRetryable());
        }
    }

    @Test
    public void serverError_followsDetails() {
        Recorder r = new Recorder();
        repo.analyze(paths(ME, 1), r);
        pending.get(0).onError(new java.io.IOException("offline"));
        assertEquals(Reason.NETWORK, r.failures.get(0).getReason());
    }

    @Test
    public void callbackOnlyOnce_andNothingAfterCancel() {
        Recorder r = new Recorder();
        repo.analyze(paths(ME, 1), r);
        pending.get(0).onSuccess(wrapped(1));
        pending.get(0).onError(new IllegalStateException("late"));
        assertEquals(1, r.count());

        Recorder cancelled = new Recorder();
        TimetableRecognitionRepository.Registration reg = repo.analyze(paths(ME, 1), cancelled);
        reg.cancel();
        pending.get(1).onSuccess(wrapped(1));
        pending.get(1).onError(new IllegalStateException("x"));
        assertEquals(0, cancelled.count());
        reg.cancel(); // 두 번 불러도 괜찮다
    }

    @Test
    public void perCourseWarningsKey_isIgnored() {
        Map<String, Object> envelope = wrapped(1);
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        @SuppressWarnings("unchecked") List<Map<String, Object>> courses = (List<Map<String, Object>>) data.get("courses");
        for (Map<String, Object> c : courses) c.put("warnings", Arrays.asList("x"));
        Recorder r = new Recorder();
        repo.analyze(paths(ME, 1), r);
        pending.get(0).onSuccess(envelope);
        assertEquals(4, r.results.get(0).courses.size());
        assertTrue(r.failures.isEmpty());
    }
}
