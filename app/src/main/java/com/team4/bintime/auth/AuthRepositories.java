package com.team4.bintime.auth;

import android.content.Context;

import com.google.firebase.FirebaseApp;

/**
 * 인증 저장소 선택 (BT-013).
 * app/google-services.json 이 있어 Firebase 가 초기화되면 실제 Firebase Auth 를,
 * 없으면(CI·설정 전 팀원) 기존 MockAuthRepository 를 쓴다. 두 경우 모두 앱 전역에서 같은 인스턴스다.
 */
public final class AuthRepositories {

    /** 마지막으로 선택된 저장소. Context 없이 UID 가 필요한 Mock 저장소들이 쓴다. */
    private static volatile AuthRepository selected;

    private AuthRepositories() {}

    public static boolean isFirebaseEnabled(Context context) {
        return !FirebaseApp.getApps(context).isEmpty();
    }

    public static AuthRepository get(Context context) {
        AuthRepository repository = isFirebaseEnabled(context)
                ? FirebaseAuthRepository.get(context)
                : MockAuthRepository.shared();
        selected = repository;
        return repository;
    }

    /**
     * Context 가 없는 곳에서 현재 로그인 UID 를 얻는다.
     * 프로세스 복원으로 중간 화면(예: 시간표 등록)부터 열려 {@link #get} 이 아직 불리지 않았어도,
     * Firebase 가 초기화돼 있으면 그 앱 Context 로 저장소를 골라 Firebase UID 를 돌려준다.
     */
    public static String currentUid() {
        AuthRepository repository = selected;
        if (repository == null) repository = selectWithoutContext();
        return repository.getCurrentUid();
    }

    private static AuthRepository selectWithoutContext() {
        try {
            // google-services 플러그인이 적용되면 앱 시작 시 FirebaseApp 이 자동 초기화된다.
            return get(FirebaseApp.getInstance().getApplicationContext());
        } catch (RuntimeException notInitialized) {
            // Firebase 미설정(CI·설정 전 팀원) 또는 JVM 단위 테스트: Mock 기준
            return MockAuthRepository.shared();
        }
    }
}
