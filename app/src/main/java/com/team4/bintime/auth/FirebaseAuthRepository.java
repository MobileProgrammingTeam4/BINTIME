package com.team4.bintime.auth;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.ActionCodeSettings;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;

import java.util.Locale;

/**
 * Firebase Authentication 비밀번호 없는 이메일 링크 로그인 (BT-013).
 * sendEmailVerification() 기반 비밀번호 계정 확인이 아니라 sendSignInLinkToEmail / signInWithEmailLink 를 쓴다.
 * 링크는 Firebase Hosting 기본 도메인(PROJECT_ID.firebaseapp.com)의 /__/auth/links 로 앱에 돌아온다.
 * 요청 이메일은 이 기기 SharedPreferences 에만 저장하고 링크 URL 에 넣지 않는다 (세션 주입 방지, Firebase 권장).
 */
public final class FirebaseAuthRepository implements AuthRepository {

    private static final String PREFS = "bintime_auth";
    private static final String KEY_PENDING_EMAIL = "pendingEmail";

    private static FirebaseAuthRepository instance;

    public static synchronized FirebaseAuthRepository get(Context context) {
        if (instance == null) instance = new FirebaseAuthRepository(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final FirebaseAuth auth;

    private FirebaseAuthRepository(Context context) {
        this.context = context;
        this.auth = FirebaseAuth.getInstance();
        this.auth.setLanguageCode("ko");
    }

    @Override
    public void sendSignInLink(String email, Callback<Void> callback) {
        if (!EmailVerificationState.isValidUniversityEmail(email)) {
            fail(callback, Failure.INVALID_EMAIL);
            return;
        }
        String normalized = normalize(email);
        String projectId = FirebaseApp.getInstance().getOptions().getProjectId();
        ActionCodeSettings settings = ActionCodeSettings.newBuilder()
                .setUrl("https://" + projectId + ".firebaseapp.com/finishSignIn")
                .setHandleCodeInApp(true)
                .setAndroidPackageName(context.getPackageName(), true, null)
                .build();
        auth.sendSignInLinkToEmail(normalized, settings).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                prefs().edit().putString(KEY_PENDING_EMAIL, normalized).apply();
                if (callback != null) callback.onSuccess(null);
            } else {
                fail(callback, toFailure(task.getException()));
            }
        });
    }

    @Override
    public void resendSignInLink(String email, Callback<Void> callback) {
        // 새 링크를 보내면 이전 링크는 서버에서 무효가 된다.
        sendSignInLink(email, callback);
    }

    @Override
    public void verifySignInLink(String email, String link, Callback<String> callback) {
        if (!EmailVerificationState.isValidUniversityEmail(email)) {
            fail(callback, Failure.INVALID_EMAIL);
            return;
        }
        if (link == null || !auth.isSignInWithEmailLink(link)) {
            fail(callback, Failure.INVALID_LINK);
            return;
        }
        auth.signInWithEmailLink(normalize(email), link).addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && task.getResult().getUser() != null) {
                prefs().edit().remove(KEY_PENDING_EMAIL).apply();
                if (callback != null) callback.onSuccess(task.getResult().getUser().getUid());
            } else {
                fail(callback, toFailure(task.getException()));
            }
        });
    }

    @Override
    public boolean isSignedIn() {
        return auth.getCurrentUser() != null;
    }

    @Override
    public String getCurrentUid() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    @Override
    public String getCurrentEmail() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getEmail() : null;
    }

    @Override
    public void signOut() {
        auth.signOut();
        prefs().edit().remove(KEY_PENDING_EMAIL).apply();
    }

    @Override
    public String getPendingEmail() {
        return prefs().getString(KEY_PENDING_EMAIL, null);
    }

    @Override
    public boolean isSignInLink(String link) {
        return link != null && auth.isSignInWithEmailLink(link);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Failure toFailure(Exception e) {
        if (e instanceof FirebaseNetworkException) return Failure.NETWORK;
        if (e instanceof FirebaseTooManyRequestsException) return Failure.TOO_MANY_REQUESTS;
        if (e instanceof FirebaseAuthException) return FirebaseAuthErrors.fromCode(((FirebaseAuthException) e).getErrorCode());
        return Failure.UNKNOWN;
    }

    private static <T> void fail(Callback<T> callback, Failure failure) {
        if (callback != null) callback.onFailure(failure, FirebaseAuthErrors.message(failure));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
