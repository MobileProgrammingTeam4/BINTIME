package com.team4.bintime.data.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.recognition.RecognitionFailure.Reason;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** [BT-026] Mock 인식 저장소(동기): 서버 mock 과 같은 데이터, 경로 소유·개수 검사, 실패 주입, 오류 사유 매핑. */
public class MockTimetableRecognitionRepositoryTest {

    private String uid = "uid-me";
    private final MockTimetableRecognitionRepository repo = new MockTimetableRecognitionRepository(() -> uid);

    private static List<String> paths(String uid, int n) {
        List<String> p = new ArrayList<>();
        for (int i = 0; i < n; i++) p.add("timetableImages/" + uid + "/batch-1/" + i + ".jpg");
        return p;
    }

    private Recorder analyze(List<String> paths) {
        Recorder r = new Recorder();
        repo.analyze(paths, r);
        return r;
    }

    @Test
    public void twoImages_returnServerMockData() {
        Recorder r = analyze(paths("uid-me", 2));
        assertNull(r.failure);
        assertEquals(4, r.result.courses.size());
        assertEquals(2, r.result.imageCount);
        assertEquals(1, r.result.courses.get(2).sourceImageIndex); // 마지막 이미지의 "자료구조"
        assertEquals(0.52, r.result.courses.get(3).confidence, 1e-9);
        assertNull(r.result.courses.get(3).building);
        assertEquals(Collections.singletonList("여러 이미지에서 같은 수업이 발견되었습니다."), r.result.warnings);
    }

    @Test
    public void oneImage_hasNoWarning_andAllFromImageZero() {
        Recorder r = analyze(paths("uid-me", 1));
        assertTrue(r.result.warnings.isEmpty());
        for (RecognizedCourse c : r.result.courses) assertEquals(0, c.sourceImageIndex);
    }

    @Test
    public void notSignedIn_otherUsersPath_badCount_fail() {
        assertEquals(Reason.PERMISSION_DENIED, analyze(paths("uid-other", 1)).failure.getReason());
        assertEquals(Reason.PERMISSION_DENIED,
                analyze(Collections.singletonList("timetableImages/uid-me/../uid-other/0.jpg")).failure.getReason());
        assertEquals(Reason.INVALID_INPUT, analyze(Collections.emptyList()).failure.getReason());
        assertEquals(Reason.INVALID_INPUT, analyze(paths("uid-me", 6)).failure.getReason());
        uid = null;
        assertEquals(Reason.UNAUTHENTICATED, analyze(paths("uid-me", 1)).failure.getReason());
    }

    @Test
    public void injectedFailure_appliesOnce() {
        repo.failNext(RecognitionFailure.of(Reason.AI_TIMEOUT));
        Recorder failed = analyze(paths("uid-me", 1));
        assertEquals(Reason.AI_TIMEOUT, failed.failure.getReason());
        assertTrue(failed.failure.isRetryable());
        assertNull(analyze(paths("uid-me", 1)).failure);
        assertEquals(2, repo.calls());
    }

    @Test
    public void malformedResponse_isInvalidOutput() {
        repo.respondNextWith("not a map");
        assertEquals(Reason.AI_INVALID_OUTPUT, analyze(paths("uid-me", 1)).failure.getReason());
    }

    @Test
    public void serverReasons_mapWithRetryableDefaultsAndOverride() {
        for (Reason r : Reason.values()) {
            if (r == Reason.NETWORK) {
                assertEquals(Reason.INTERNAL, Reason.fromServer("NETWORK")); // 앱 전용 사유는 서버 값이 아니다
                continue;
            }
            assertEquals(r, RecognitionFailure.fromServer(r.name(), null, null).getReason());
        }
        assertEquals(Reason.INTERNAL, RecognitionFailure.fromServer("SOMETHING_NEW", null, null).getReason());
        assertEquals(Reason.INTERNAL, RecognitionFailure.fromServer(null, null, null).getReason());

        List<Reason> retryable = Arrays.asList(Reason.AI_RATE_LIMITED, Reason.AI_TIMEOUT, Reason.AI_INVALID_OUTPUT,
                Reason.AI_UNAVAILABLE, Reason.NETWORK);
        for (Reason r : Reason.values()) {
            assertEquals(r.name(), retryable.contains(r), RecognitionFailure.of(r).isRetryable());
        }
        assertFalse(RecognitionFailure.fromServer("AI_UNAVAILABLE", false, "req").isRetryable());
        assertTrue(RecognitionFailure.fromServer("AI_TIMEOUT", null, "req").isRetryable());
        assertEquals("req", RecognitionFailure.fromServer("AI_TIMEOUT", null, "req").getRequestId());
        assertEquals(-1, RecognitionFailure.fromServer("AI_TIMEOUT", null, "req-secret").toString()
                .indexOf("req-secret"));
    }

    private static final class Recorder implements TimetableRecognitionRepository.Callback {
        RecognitionResult result;
        RecognitionFailure failure;

        @Override
        public void onSuccess(RecognitionResult result) {
            this.result = result;
        }

        @Override
        public void onError(RecognitionFailure failure) {
            this.failure = failure;
        }
    }
}
