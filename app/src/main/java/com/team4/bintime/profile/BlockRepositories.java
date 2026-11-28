package com.team4.bintime.profile;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/** 차단 저장소 선택 (BT-046). 인증·프로필 저장소와 같은 기준으로 Firebase/Mock 을 고른다. */
public final class BlockRepositories {

    private BlockRepositories() {}

    public static BlockRepository get(Context context) {
        return AuthRepositories.isFirebaseEnabled(context)
                ? FirebaseBlockRepository.get()
                : MockBlockRepository.shared(MockProfileRepository.shared());
    }
}
