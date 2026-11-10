package com.team4.bintime.auth;

/**
 * 학교 이메일 링크 인증 및 계정 관리 인터페이스.
 * Sprint 2에서 Firebase Authentication 연동 시 구현체를 교체합니다.
 */
public interface AuthRepository {

    /** 인증 실패 사유. 화면은 이 값으로 만료/잘못된 링크/이메일 재입력 상태를 구분한다. */
    enum Failure {
        INVALID_EMAIL,   // 허용되지 않은 이메일 형식·도메인
        LINK_NOT_SENT,   // 인증 링크를 발송한 적이 없음
        INVALID_LINK,    // 발송한 링크와 다르거나 형식이 잘못된 링크
        LINK_EXPIRED,    // 유효 시간이 지난 링크
        LINK_ALREADY_USED, // 이미 인증에 사용된 링크
        EMAIL_MISMATCH,  // 링크를 요청한 이메일과 입력한 이메일이 다름
        TOO_MANY_REQUESTS, // 발송 요청이 너무 잦음 (서버 제한)
        NETWORK,         // 네트워크 오류
        UNKNOWN          // 그 밖의 실패
    }

    /** 이 기기에서 마지막으로 링크를 요청한 이메일. 같은 기기에서 링크를 열면 재입력 없이 인증한다. 없으면 null. */
    default String getPendingEmail() {
        return null;
    }

    /** 앱으로 들어온 링크가 이메일 로그인 링크인지 확인한다. */
    default boolean isSignInLink(String link) {
        return link != null && !link.isEmpty();
    }

    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);

        /** 실패 사유가 필요한 화면은 이 메서드를 재정의한다. 기본 동작은 onError 위임. */
        default void onFailure(Failure failure, String message) {
            onError(message);
        }
    }

    /** 학교 이메일로 Firebase Auth 로그인 링크를 전송합니다. */
    void sendSignInLink(String email, Callback<Void> callback);

    /** 인증 링크 재발송. 이전에 발송한 링크는 더 이상 사용할 수 없습니다. */
    void resendSignInLink(String email, Callback<Void> callback);

    /**
     * 수신된 링크(또는 딥링크 URL)를 검증하고 로그인을 완료합니다.
     * 링크 발송 여부, 링크 일치·만료·재사용, 요청 이메일과의 일치 여부를 모두 통과해야 성공합니다.
     */
    void verifySignInLink(String email, String link, Callback<String /* uid */> callback);

    /** 현재 사용자의 로그인 여부 확인 */
    boolean isSignedIn();

    /** 현재 로그인된 사용자 UID 반환 */
    String getCurrentUid();

    /** 로그아웃 */
    void signOut();
}
