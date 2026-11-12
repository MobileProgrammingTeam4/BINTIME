package com.team4.bintime.data.repository;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/**
 * [BT-015] 시간표 저장소 선택. 인증 저장소와 같은 기준({@link AuthRepositories#isFirebaseEnabled})으로
 * Firestore 구현과 Mock을 고른다. 두 경우 모두 앱 전역에서 같은 인스턴스다.
 * UID는 항상 {@code AuthRepositories.get(context).getCurrentUid()}로 얻는다.
 */
public final class TimetableRepositories {

    private static MockAsyncTimetableRepository mock;

    private TimetableRepositories() {
    }

    public static AsyncTimetableRepository get(Context context) {
        Context app = context.getApplicationContext();
        if (AuthRepositories.isFirebaseEnabled(app)) return FirestoreTimetableRepository.get(app);
        synchronized (TimetableRepositories.class) {
            if (mock == null) {
                mock = new MockAsyncTimetableRepository(MockTimetableRepositoryImpl.getInstance(),
                        () -> AuthRepositories.get(app).getCurrentUid());
            }
            return mock;
        }
    }
}
