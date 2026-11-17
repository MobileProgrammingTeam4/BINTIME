package com.team4.bintime.auth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.team4.bintime.R;
import com.team4.bintime.profile.ProfileRepositories;

/**
 * BT-030 로그인 세션 감시. 메인 화면이 보이는 동안·다시 보일 때 로그인이 끊겼으면(계정 삭제·비활성화·토큰 만료로
 * Firebase 가 로그아웃시킨 경우) 온보딩으로 보내 "다시 로그인"하게 한다.
 * <p>
 * 사용자가 마이페이지에서 직접 로그아웃한 경우는 {@link #signOutByUser} 로 표시해, 감시가 같은 이동을 한 번 더 하거나
 * "만료" 안내를 잘못 띄우지 않게 한다. Mock 모드는 로그인을 메모리에만 두므로 감시하지 않는다.
 */
public final class SessionGuard {

    /** 직접 로그아웃 직후 한 번 오는 로그아웃 이벤트는 만료로 보지 않는다. 메인 스레드에서만 쓴다. */
    private static boolean userSignOutInProgress;

    private SessionGuard() {}

    /** 감시 해제 핸들. */
    public interface Watch {
        void stop();
    }

    /**
     * Firebase 모드에서 로그아웃 상태면 온보딩으로 보내고 true(호출한 화면은 더 그리지 않는다).
     * 화면이 다시 보일 때(onStart) 부른다.
     */
    public static boolean redirectIfSignedOut(Activity activity) {
        if (!AuthRepositories.isFirebaseEnabled(activity)) return false;
        if (AuthRepositories.get(activity).isSignedIn()) return false;
        goToOnboarding(activity, !userSignOutInProgress);
        return true;
    }

    /** 화면이 보이는 동안 로그인이 끊기면 온보딩으로 보낸다. onStart 에서 시작하고 onStop 에서 멈춘다. */
    public static Watch watch(Activity activity) {
        if (!AuthRepositories.isFirebaseEnabled(activity)) return () -> {};
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseAuth.AuthStateListener listener = firebaseAuth -> {
            if (firebaseAuth.getCurrentUser() != null) return;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (userSignOutInProgress) return; // 직접 로그아웃: 마이페이지가 이미 이동한다.
            goToOnboarding(activity, true);
        };
        auth.addAuthStateListener(listener);
        return () -> auth.removeAuthStateListener(listener);
    }

    /**
     * 사용자가 직접 로그아웃한다: 세션 캐시 정리 → 로그아웃 → 온보딩(이전 화면 모두 닫음).
     * 다음 계정이 이전 계정의 화면·캐시를 보지 않게 한다.
     */
    public static void signOutByUser(Activity activity) {
        userSignOutInProgress = true;
        ProfileRepositories.get(activity).clearSessionCaches();
        AuthRepositories.get(activity).signOut();
        Intent intent = new Intent(activity, OnboardingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
    }

    /** 다시 로그인한 뒤에는 만료 감시를 정상으로 되돌린다(온보딩·인증 화면에서 부름). */
    static void resetUserSignOut() {
        userSignOutInProgress = false;
    }

    private static void goToOnboarding(Activity activity, boolean expired) {
        Context app = activity.getApplicationContext();
        if (expired) Toast.makeText(app, R.string.session_expired, Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(activity, OnboardingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }
}
