package com.team4.bintime.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

    @Test
    public void everyFailure_hasMessage() {
        for (AuthRepository.Failure failure : AuthRepository.Failure.values()) {
            assertFalse(FirebaseAuthErrors.message(failure).isEmpty());
        }
    }
}
