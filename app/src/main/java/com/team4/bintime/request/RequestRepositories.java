package com.team4.bintime.request;

import android.content.Context;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.auth.AuthRepository;
import com.team4.bintime.auth.MockAuthRepository;
import java.util.Objects;

/** 매칭 카드·상세·요청 탭이 로그인 사용자별로 같은 저장소를 사용한다. */
public final class RequestRepositories {
    private static RequestRepository selected;
    private static String selectedUid;
    private RequestRepositories() {}

    public static synchronized RequestRepository get(Context context) {
        String uid = AuthRepositories.get(context).getCurrentUid();
        boolean firebase = AuthRepositories.isFirebaseEnabled(context);
        if (selected == null || !Objects.equals(selectedUid, uid) || selected.isMock() == firebase) {
            selectedUid = uid;
            selected = firebase ? new FirebaseRequestRepository(uid) : new MockRequestRepository(uid, true);
        }
        return selected;
    }

    public static synchronized void restoreMock(Context context, MockRequestRepository restored) {
        if (restored == null || AuthRepositories.isFirebaseEnabled(context)) return;
        AuthRepository auth = AuthRepositories.get(context);
        String uid = auth.getCurrentUid();
        if (uid == null && auth instanceof MockAuthRepository
                && ((MockAuthRepository) auth).restoreSession(restored.ownerUid)) {
            uid = auth.getCurrentUid();
        }
        if (uid != null && Objects.equals(uid, restored.ownerUid)) {
            selectedUid = uid;
            selected = restored;
        }
    }
}
