package com.team4.bintime.auth;

/**
 * Firebase Auth 오류 코드를 화면 상태용 실패 사유로 바꾼다. (BT-013)
 * Android 의존성이 없어 단위 테스트로 검증한다.
 */
public final class FirebaseAuthErrors {

    private FirebaseAuthErrors() {}

    public static AuthRepository.Failure fromCode(String errorCode) {
        if (errorCode == null) return AuthRepository.Failure.UNKNOWN;
        switch (errorCode) {
            case "ERROR_EXPIRED_ACTION_CODE":
                return AuthRepository.Failure.LINK_EXPIRED;
            case "ERROR_INVALID_ACTION_CODE":
                // 형식이 틀렸거나 이미 사용·재발송으로 무효가 된 링크
                return AuthRepository.Failure.INVALID_LINK;
            case "ERROR_INVALID_EMAIL":
                // 이메일 링크 로그인에서는 링크를 받은 이메일과 입력 이메일이 다를 때 발생
                return AuthRepository.Failure.EMAIL_MISMATCH;
            case "ERROR_TOO_MANY_REQUESTS":
                return AuthRepository.Failure.TOO_MANY_REQUESTS;
            case "ERROR_USER_DISABLED":
                return AuthRepository.Failure.ACCOUNT_DISABLED;
            default:
                return AuthRepository.Failure.UNKNOWN;
        }
    }

    /**
     * BT-030: 세션 확인(user.reload) 실패 코드 중 "다시 로그인해야 하는" 것.
     * 계정 삭제·비활성화·토큰 만료/무효만 해당하고, 네트워크·일시 오류는 아니다(오프라인에서 로그아웃시키지 않음).
     */
    public static boolean isSessionInvalid(String errorCode) {
        if (errorCode == null) return false;
        switch (errorCode) {
            case "ERROR_USER_NOT_FOUND":
            case "ERROR_USER_DISABLED":
            case "ERROR_USER_TOKEN_EXPIRED":
            case "ERROR_INVALID_USER_TOKEN":
                return true;
            default:
                return false;
        }
    }

    /**
     * BT-030: 세션 확인 실패 코드 → 세션 상태. 사용 중지는 DISABLED("만료"가 아님), 삭제·토큰 무효는 EXPIRED,
     * 그 밖(네트워크 등)은 UNKNOWN(로그아웃시키지 않음).
     */
    public static AuthRepository.SessionState sessionStateFor(String errorCode) {
        if ("ERROR_USER_DISABLED".equals(errorCode)) return AuthRepository.SessionState.DISABLED;
        if (isSessionInvalid(errorCode)) return AuthRepository.SessionState.EXPIRED;
        return AuthRepository.SessionState.UNKNOWN;
    }

    public static String message(AuthRepository.Failure failure) {
        switch (failure) {
            case LINK_EXPIRED: return "인증 링크가 만료되었어요.";
            case INVALID_LINK:
            case LINK_ALREADY_USED: return "잘못되었거나 이미 사용된 인증 링크예요.";
            case EMAIL_MISMATCH: return "인증 링크를 받은 이메일과 일치하지 않아요.";
            case INVALID_EMAIL: return "허용된 대학교 이메일 도메인이 아닙니다.";
            case LINK_NOT_SENT: return "먼저 인증 링크를 발송해 주세요.";
            case TOO_MANY_REQUESTS: return "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.";
            case ACCOUNT_DISABLED: return "사용이 중지된 계정이에요. 관리자에게 문의해 주세요.";
            case NETWORK: return "네트워크 연결을 확인해 주세요.";
            default: return "인증에 실패했어요. 다시 시도해 주세요.";
        }
    }
}
