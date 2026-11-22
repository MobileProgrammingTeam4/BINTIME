package com.team4.bintime.data.recognition;

import android.content.Context;

import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableReference;
import com.team4.bintime.auth.AuthRepositories;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * [BT-026 P4] 실제 인식: analyzeTimetable Callable(region asia-northeast3, docs/BT-024.md 게이트웨이).
 * <ul>
 *   <li>입력은 {@code {imagePaths}} 하나다(BT-025 에서 올린 본인 경로, 선택 순서 = sourceImageIndex).
 *       UID 는 보내지 않는다(서버가 토큰으로 확인).</li>
 *   <li>보내기 전에 Mock 과 같은 규칙(로그인, 1~5장, 길이, 본인 접두사)을 본다. 틀리면 서버를 부르지 않아
 *       하루 횟수를 쓰지 않는다.</li>
 *   <li>타임아웃은 {@link #TIMEOUT_SECONDS}(서버 함수 120초 + 응답 여유). 앱이 먼저 끝나도 서버는 계속 돌 수 있고
 *       차감된 횟수는 돌아오지 않는다.</li>
 *   <li>응답은 {@link RecognitionParser#parseCallable}, 오류는 {@link RecognitionCallableErrors}로 바꾼다.</li>
 *   <li>{@link Registration#cancel()}은 결과를 더 받지 않을 뿐 서버 호출을 멈추지 않는다. 취소하면 콜백 참조를
 *       버려 화면 상태를 붙잡지 않는다.</li>
 * </ul>
 * 콜백은 main 스레드(Task 기본 실행기)에서 불린다.
 */
public final class FirebaseTimetableRecognitionRepository implements TimetableRecognitionRepository {

    static final String REGION = "asia-northeast3";
    static final String FUNCTION = "analyzeTimetable";
    static final long TIMEOUT_SECONDS = 130;

    /** Callable 호출을 감싼다(테스트에서 가짜로 바꾼다). */
    interface Caller {
        void call(Map<String, Object> data, long timeoutSeconds, Listener listener);
    }

    interface Listener {
        /** Callable 결과 데이터(HttpsCallableResult.getData()). */
        void onSuccess(Object result);

        void onError(Exception error);
    }

    private static FirebaseTimetableRecognitionRepository instance;

    private final Supplier<String> currentUid;
    private final Caller caller;

    FirebaseTimetableRecognitionRepository(Supplier<String> currentUid, Caller caller) {
        this.currentUid = currentUid;
        this.caller = caller;
    }

    public static synchronized FirebaseTimetableRecognitionRepository get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            instance = new FirebaseTimetableRecognitionRepository(
                    () -> AuthRepositories.get(app).getCurrentUid(), FirebaseTimetableRecognitionRepository::callFirebase);
        }
        return instance;
    }

    private static void callFirebase(Map<String, Object> data, long timeoutSeconds, Listener listener) {
        HttpsCallableReference callable = FirebaseFunctions.getInstance(REGION).getHttpsCallable(FUNCTION);
        callable.setTimeout(timeoutSeconds, TimeUnit.SECONDS);
        callable.call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                listener.onSuccess(task.getResult() == null ? null : task.getResult().getData());
            } else {
                listener.onError(task.getException());
            }
        });
    }

    @Override
    public Registration analyze(List<String> imagePaths, Callback callback) {
        String uid = currentUid == null ? null : currentUid.get();
        RecognitionFailure invalid = MockTimetableRecognitionRepository.check(uid, imagePaths);
        if (invalid != null) {
            callback.onError(invalid);
            return () -> { };
        }
        List<String> paths = new ArrayList<>(imagePaths);
        Call call = new Call(callback, paths.size());
        Map<String, Object> data = new HashMap<>();
        data.put("imagePaths", paths);
        caller.call(data, TIMEOUT_SECONDS, call);
        return call;
    }

    /** 한 번의 호출. 결과는 한 번만 넘기고, 취소 뒤에는 넘기지 않는다. */
    private static final class Call implements Registration, Listener {
        private Callback callback;
        private final int imageCount;

        Call(Callback callback, int imageCount) {
            this.callback = callback;
            this.imageCount = imageCount;
        }

        @Override
        public void cancel() {
            callback = null;
        }

        @Override
        public void onSuccess(Object result) {
            Callback target = take();
            if (target == null) return;
            RecognitionResult parsed = RecognitionParser.parseCallable(result, imageCount);
            if (parsed == null) target.onError(RecognitionFailure.of(RecognitionFailure.Reason.AI_INVALID_OUTPUT));
            else target.onSuccess(parsed);
        }

        @Override
        public void onError(Exception error) {
            Callback target = take();
            if (target != null) target.onError(RecognitionCallableErrors.fromException(error));
        }

        private Callback take() {
            Callback target = callback;
            callback = null;
            return target;
        }
    }
}
