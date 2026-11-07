package com.team4.bintime.auth;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 * Sprint 1 UI 목업용 가상 인증 저장소.
 * 6자리 OTP 없이 이메일 링크 발송 및 상태 전환 흐름을 지원합니다.
 */
public class MockAuthRepository implements AuthRepository, Serializable {

    private String currentUid = null;
    private String pendingEmail = null;
    private final Set<String> registeredEmails = new HashSet<>();

    public MockAuthRepository() {
        // 기존 가입된 계정 예시 (1이메일 1계정 정책 확인용)
        registeredEmails.add("yu.bin@kookmin.ac.kr");
    }

    @Override
    public void sendSignInLink(String email, Callback<Void> callback) {
        if (!EmailVerificationState.isValidUniversityEmail(email)) {
            if (callback != null) {
                callback.onError("허용된 대학교 이메일 도메인이 아닙니다.");
            }
            return;
        }

        this.pendingEmail = email.trim();
        if (callback != null) {
            callback.onSuccess(null);
        }
    }

    @Override
    public void resendSignInLink(String email, Callback<Void> callback) {
        sendSignInLink(email, callback);
    }

    @Override
    public void verifySignInLink(String email, String link, Callback<String> callback) {
        if (email == null || email.trim().isEmpty()) {
            if (callback != null) {
                callback.onError("이메일 정보가 누락되었습니다.");
            }
            return;
        }

        this.currentUid = "mock_uid_" + Math.abs(email.hashCode());
        this.registeredEmails.add(email.trim());
        this.pendingEmail = null;

        if (callback != null) {
            callback.onSuccess(this.currentUid);
        }
    }

    @Override
    public boolean isSignedIn() {
        return currentUid != null;
    }

    @Override
    public String getCurrentUid() {
        return currentUid;
    }

    @Override
    public void signOut() {
        currentUid = null;
        pendingEmail = null;
    }

    public String getPendingEmail() {
        return pendingEmail;
    }

    public void setPendingEmail(String pendingEmail) {
        this.pendingEmail = pendingEmail;
    }

    public boolean isRegisteredEmail(String email) {
        return email != null && registeredEmails.contains(email.trim());
    }
}
