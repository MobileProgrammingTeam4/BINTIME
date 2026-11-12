package com.team4.bintime.profile;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/**
 * 프로필 저장소 선택 (BT-014). 인증 저장소와 같은 기준으로 Firebase/Mock 을 고른다.
 */
public final class ProfileRepositories {

    private ProfileRepositories() {}

    public static ProfileRepository get(Context context) {
        return AuthRepositories.isFirebaseEnabled(context)
                ? FirebaseProfileRepository.get()
                : MockProfileRepository.shared();
    }
}
