package com.team4.bintime.data.repository;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/**
 * [BT-025] 시간표 이미지 저장소 선택.
 * <p>
 * [P2] {@link TimetableRepositories}와 같은 기준({@code AuthRepositories.isFirebaseEnabled})으로 Firebase 빌드면
 * 실제 Storage 구현({@link FirebaseTimetableImageRepository}), 아니면 Mock 을 고른다. 두 경우 모두 앱 전역 하나다.
 * 로그인 UID가 없어도 Mock 으로 바꾸지 않는다(가짜 성공 방지). 구현이 호출마다
 * {@code AuthRepositories.get(context).getCurrentUid()}를 확인해 없으면 {@code NOT_SIGNED_IN}으로 끝낸다.
 */
public final class TimetableImageRepositories {

    private static MockTimetableImageRepository mock;

    private TimetableImageRepositories() {
    }

    public static TimetableImageRepository get(Context context) {
        Context app = context.getApplicationContext();
        if (AuthRepositories.isFirebaseEnabled(app)) return FirebaseTimetableImageRepository.get(app);
        synchronized (TimetableImageRepositories.class) {
            if (mock == null) {
                mock = new MockTimetableImageRepository(() -> AuthRepositories.get(app).getCurrentUid());
            }
            return mock;
        }
    }
}
