package com.team4.bintime.data.repository;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/**
 * [BT-025] 시간표 이미지 저장소 선택.
 * <p>
 * <b>P1(현재): 항상 Mock이다.</b> Firebase 빌드에서도 실제 Storage에 올리지 않는다. Storage Rules
 * ({@code timetableImages/})가 게시되기 전에는 실제 업로드가 기본 거부되기 때문이다. Rules 게시 후 P2에서
 * {@link TimetableRepositories}와 같은 기준({@code AuthRepositories.isFirebaseEnabled})으로 Firebase 구현을 고른다.
 * UID는 {@code AuthRepositories.get(context).getCurrentUid()}로 얻는다.
 */
public final class TimetableImageRepositories {

    private static MockTimetableImageRepository mock;

    private TimetableImageRepositories() {
    }

    public static TimetableImageRepository get(Context context) {
        Context app = context.getApplicationContext();
        synchronized (TimetableImageRepositories.class) {
            if (mock == null) {
                mock = new MockTimetableImageRepository(() -> AuthRepositories.get(app).getCurrentUid());
            }
            return mock;
        }
    }
}
