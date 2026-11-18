package com.team4.bintime.data.recognition;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/**
 * [BT-026] 인식 저장소 선택.
 * <p>
 * <b>P3(현재): 항상 Mock이다.</b> analyzeTimetable 의 live 프롬프트가 아직 없어(서버가 AI_NOT_IMPLEMENTED) 실제
 * Callable 연결은 P4에서 한다. 이미지 업로드도 BT-025 P2 전에는 Mock 이라 실제 경로가 없다.
 * UID는 {@code AuthRepositories.get(context).getCurrentUid()}로 얻는다.
 */
public final class TimetableRecognitionRepositories {

    private static MockTimetableRecognitionRepository mock;

    private TimetableRecognitionRepositories() {
    }

    public static TimetableRecognitionRepository get(Context context) {
        Context app = context.getApplicationContext();
        synchronized (TimetableRecognitionRepositories.class) {
            if (mock == null) {
                mock = new MockTimetableRecognitionRepository(() -> AuthRepositories.get(app).getCurrentUid());
            }
            return mock;
        }
    }
}
