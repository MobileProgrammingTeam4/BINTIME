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
        ACCOUNT_DISABLED, // 관리자가 사용 중지한 계정 (BT-030). 다시 로그인해도 안 되므로 "만료"와 구분한다
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

    /** 인증된 계정의 이메일. 알 수 없으면 null (Mock). */
    default String getCurrentEmail() {
        return null;
    }

    /** 로그아웃 */
    void signOut();

    /** BT-045 탈퇴 실패 문구. */
    String MSG_DELETE_FAILED = "탈퇴를 마치지 못했어요. 잠시 후 다시 시도해 주세요.";
    String MSG_DELETE_NETWORK = "네트워크 연결을 확인해 주세요.";

    /**
     * BT-045 회원 탈퇴. 서버(deleteAccount)가 개인 데이터 삭제·대기 요청/진행 매칭 정리·채팅방 나가기 후 계정을 지운다.
     * 성공해도 기기 로그인은 그대로이므로 호출한 쪽에서 {@link SessionGuard#signOutByUser} 로 정리한다.
     * 실패하면 다시 호출하면 된다(서버가 남은 단계부터 이어서 한다). 콜백은 정확히 한 번, 메인 스레드로 온다.
     */
    default void deleteAccount(Callback<Void> callback) {
        callback.onError(MSG_DELETE_FAILED);
    }

    /** BT-030 세션 확인 결과. */
    enum SessionState {
        /** 로그인이 유효하다. */
        VALID,
        /** 계정이 삭제됐거나 토큰이 무효다. 이미 로그아웃 처리됐으니 다시 로그인해야 한다. */
        EXPIRED,
        /** 관리자가 계정을 사용 중지했다. 로그아웃 처리됐고, 다시 로그인해도 막히므로 "만료"와 다르게 안내한다. */
        DISABLED,
        /** 네트워크 등으로 확인하지 못했다. 기기에 남은 로그인으로 계속 진행한다(오프라인에서 막지 않음). */
        UNKNOWN
    }

    /**
     * 기기에 남은 로그인이 서버에서도 유효한지 확인한다(BT-030). 앱 시작 라우팅에서 쓴다.
     * 기본 구현(Mock)은 로그인 여부만 본다. 콜백은 정확히 한 번 호출된다.
     */
    default void checkSession(Callback<SessionState> callback) {
        if (callback != null) callback.onSuccess(isSignedIn() ? SessionState.VALID : SessionState.EXPIRED);
    }
}
