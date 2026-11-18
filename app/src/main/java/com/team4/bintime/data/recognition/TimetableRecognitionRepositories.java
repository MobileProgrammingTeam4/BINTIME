package com.team4.bintime.data.recognition;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/**
 * [BT-026] 인식 저장소 선택.
 * <p>
 * [P4] 이미지 저장소({@code TimetableImageRepositories})와 같은 기준({@code AuthRepositories.isFirebaseEnabled})으로
 * Firebase 빌드면 실제 analyzeTimetable Callable({@link FirebaseTimetableRecognitionRepository}), 아니면 Mock 을
 * 고른다. 서버 live 프롬프트가 배포되기 전에는 실제 호출이 {@code AI_NOT_IMPLEMENTED}로 끝나고 화면은 직접 입력을 안내한다.
 * UID는 {@code AuthRepositories.get(context).getCurrentUid()}로 얻는다.
 */
public final class TimetableRecognitionRepositories {

    private static MockTimetableRecognitionRepository mock;

    private TimetableRecognitionRepositories() {
    }

    public static TimetableRecognitionRepository get(Context context) {
        Context app = context.getApplicationContext();
        if (AuthRepositories.isFirebaseEnabled(app)) return FirebaseTimetableRecognitionRepository.get(app);
        synchronized (TimetableRecognitionRepositories.class) {
            if (mock == null) {
                mock = new MockTimetableRecognitionRepository(() -> AuthRepositories.get(app).getCurrentUid());
            }
            return mock;
        }
    }
}
