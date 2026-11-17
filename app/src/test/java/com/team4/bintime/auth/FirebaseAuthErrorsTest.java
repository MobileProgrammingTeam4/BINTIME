package com.team4.bintime.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** BT-013: Firebase 오류 코드 → 화면 상태 매핑. */
public class FirebaseAuthErrorsTest {

    @Test
    public void expiredCode_mapsToLinkExpired() {
        assertEquals(AuthRepository.Failure.LINK_EXPIRED, FirebaseAuthErrors.fromCode("ERROR_EXPIRED_ACTION_CODE"));
    }

    @Test
    public void invalidCode_mapsToInvalidLink() {
        assertEquals(AuthRepository.Failure.INVALID_LINK, FirebaseAuthErrors.fromCode("ERROR_INVALID_ACTION_CODE"));
    }

    @Test
    public void invalidEmail_mapsToEmailMismatch() {
        assertEquals(AuthRepository.Failure.EMAIL_MISMATCH, FirebaseAuthErrors.fromCode("ERROR_INVALID_EMAIL"));
    }

    @Test
    public void tooManyRequests_mapsToTooManyRequests() {
        assertEquals(AuthRepository.Failure.TOO_MANY_REQUESTS, FirebaseAuthErrors.fromCode("ERROR_TOO_MANY_REQUESTS"));
    }

    @Test
    public void unknownOrNullCode_mapsToUnknown() {
        assertEquals(AuthRepository.Failure.UNKNOWN, FirebaseAuthErrors.fromCode("ERROR_SOMETHING_ELSE"));
        assertEquals(AuthRepository.Failure.UNKNOWN, FirebaseAuthErrors.fromCode(null));
    }

    /** BT-030: 계정 삭제·비활성화·토큰 무효만 "다시 로그인"이다. 네트워크·알 수 없음으로는 로그아웃시키지 않는다. */
    @Test
    public void sessionInvalid_onlyForAccountOrTokenProblems() {
        for (String code : new String[]{"ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED",
                "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN"}) {
            assertTrue(code, FirebaseAuthErrors.isSessionInvalid(code));
        }
        for (String code : new String[]{null, "ERROR_NETWORK_REQUEST_FAILED", "ERROR_TOO_MANY_REQUESTS",
                "ERROR_INVALID_ACTION_CODE", "ERROR_SOMETHING_ELSE"}) {
            assertFalse(String.valueOf(code), FirebaseAuthErrors.isSessionInvalid(code));
        }
    }

    @Test
    public void everyFailure_hasMessage() {
        for (AuthRepository.Failure failure : AuthRepository.Failure.values()) {
            assertFalse(FirebaseAuthErrors.message(failure).isEmpty());
        }
    }
}
