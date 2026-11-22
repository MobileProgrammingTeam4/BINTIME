package com.team4.bintime.data.recognition;

import com.team4.bintime.data.repository.TimetableImagePaths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * [BT-026] 인식 Mock(동기). <b>실제 인식이 아니다.</b> 서버 analyzeTimetable 의 mock 응답
 * ({@code functions/src/ai/tasks/analyzeTimetable.ts} {@code mock})과 같은 데이터를 Callable 응답 모양(Map)으로
 * 만들어 {@link RecognitionParser}를 거친다. 두 이미지 이상이면 "자료구조"가 첫·마지막 이미지에 같은 시간으로 나오고,
 * 신뢰도 0.52·건물 null 인 "확률과 통계"가 있다(중복 병합·낮은 신뢰도·강의실 없음 검수용).
 * <p>
 * 서버 authorize 와 같이 경로가 로그인 사용자 접두사({@code timetableImages/{uid}/}) 아래인지 본다.
 */
public final class MockTimetableRecognitionRepository implements TimetableRecognitionRepository {

    private final Supplier<String> currentUid;
    private RecognitionFailure nextFailure;
    private Object nextResponse;
    private boolean hasNextResponse;
    private int calls;

    public MockTimetableRecognitionRepository(Supplier<String> currentUid) {
        this.currentUid = currentUid;
    }

    @Override
    public Registration analyze(List<String> imagePaths, Callback callback) {
        calls++;
        RecognitionFailure failure = check(currentUid == null ? null : currentUid.get(), imagePaths);
        if (failure == null && nextFailure != null) {
            failure = nextFailure;
            nextFailure = null;
        }
        if (failure != null) {
            callback.onError(failure);
            return () -> { };
        }
        Object response = hasNextResponse ? nextResponse : serverMock(imagePaths.size());
        hasNextResponse = false;
        nextResponse = null;
        RecognitionResult result = RecognitionParser.parse(response, imagePaths.size());
        if (result == null) callback.onError(RecognitionFailure.of(RecognitionFailure.Reason.AI_INVALID_OUTPUT));
        else callback.onSuccess(result);
        return () -> { };
    }

    /** 서버 입력 검사·authorize 와 같은 규칙. 통과하면 null. Firebase 구현도 호출 전에 쓴다. */
    static RecognitionFailure check(String uid, List<String> paths) {
        if (uid == null) return RecognitionFailure.of(RecognitionFailure.Reason.UNAUTHENTICATED);
        if (paths == null || paths.isEmpty() || paths.size() > TimetableImagePaths.MAX_IMAGES) {
            return RecognitionFailure.of(RecognitionFailure.Reason.INVALID_INPUT);
        }
        for (String path : paths) {
            if (path == null || path.isEmpty() || path.length() > TimetableImagePaths.MAX_PATH_LENGTH) {
                return RecognitionFailure.of(RecognitionFailure.Reason.INVALID_INPUT);
            }
            if (!TimetableImagePaths.isOwnedBy(uid, path)) {
                return RecognitionFailure.of(RecognitionFailure.Reason.PERMISSION_DENIED);
            }
        }
        return null;
    }

    /** 서버 mock 과 같은 응답. */
    static Map<String, Object> serverMock(int imageCount) {
        int last = imageCount - 1;
        List<Object> courses = new ArrayList<>(Arrays.asList(
                course("컴퓨터 구조론", 1, 600, 690, "공학관", "302호", 0.94, 0),
                course("자료구조", 3, 780, 870, "미래관", "407호", 0.88, 0),
                course("자료구조", 3, 780, 870, "미래관", "407호", 0.81, last),
                course("확률과 통계", 4, 540, 630, null, null, 0.52, last)));
        Map<String, Object> response = new HashMap<>();
        response.put("courses", courses);
        response.put("warnings", last > 0
                ? Collections.singletonList("여러 이미지에서 같은 수업이 발견되었습니다.")
                : Collections.emptyList());
        return response;
    }

    private static Map<String, Object> course(String name, int day, int start, int end, String building, String room,
                                              double confidence, int source) {
        Map<String, Object> c = new HashMap<>();
        c.put("name", name);
        c.put("dayOfWeek", day);
        c.put("startMinute", start);
        c.put("endMinute", end);
        c.put("building", building);
        c.put("room", room);
        c.put("confidence", confidence);
        c.put("sourceImageIndex", source);
        return c;
    }

    // ── 테스트 전용 ───────────────────────────────────────────

    /** 다음 한 번의 호출을 이 실패로 끝낸다. */
    void failNext(RecognitionFailure failure) {
        nextFailure = failure;
    }

    /** 다음 한 번의 호출에 이 응답(Callable data)을 쓴다. */
    void respondNextWith(Object response) {
        nextResponse = response;
        hasNextResponse = true;
    }

    int calls() {
        return calls;
    }
}
