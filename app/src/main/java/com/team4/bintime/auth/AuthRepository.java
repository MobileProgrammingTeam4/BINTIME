package com.team4.bintime.auth;

/**
 * 학교 이메일 링크 인증 및 계정 관리 인터페이스.
 * Sprint 2에서 Firebase Authentication 연동 시 구현체를 교체합니다.
 */
public interface AuthRepository {

    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    /** 학교 이메일로 Firebase Auth 로그인 링크를 전송합니다. */
    void sendSignInLink(String email, Callback<Void> callback);

    /** 인증 링크 재발송 */
    void resendSignInLink(String email, Callback<Void> callback);

    /** 수신된 링크(또는 딥링크 URL)를 검증하고 로그인을 완료합니다. */
    void verifySignInLink(String email, String link, Callback<String /* uid */> callback);

    /** 현재 사용자의 로그인 여부 확인 */
    boolean isSignedIn();

    /** 현재 로그인된 사용자 UID 반환 */
    String getCurrentUid();

    /** 로그아웃 */
    void signOut();
}
