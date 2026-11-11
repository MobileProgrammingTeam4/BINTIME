package com.team4.bintime.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** BT-013 리뷰 반영: 회전 뒤 같은 검증 결과를 두 번 처리하지 않는다. */
public class EmailAuthViewModelResultTest {

    @Test
    public void success_isConsumedOnlyOnce() {
        EmailAuthViewModel.Result result = EmailAuthViewModel.Result.success("uid1", "a@kookmin.ac.kr");
        assertTrue(result.isSuccess());
        assertEquals("uid1", result.uid);
        assertEquals("a@kookmin.ac.kr", result.email);
        assertTrue(result.consume());
        assertFalse(result.consume());
    }

    @Test
    public void failure_keepsReason() {
        EmailAuthViewModel.Result result =
                EmailAuthViewModel.Result.failure(AuthRepository.Failure.LINK_EXPIRED, "만료");
        assertFalse(result.isSuccess());
        assertEquals(AuthRepository.Failure.LINK_EXPIRED, result.failure);
        assertEquals("만료", result.message);
        assertNull(result.uid);
        assertTrue(result.consume());
        assertFalse(result.consume());
    }

    @Test
    public void currentUid_withoutFirebase_fallsBackToMock() {
        // JVM 테스트에는 FirebaseApp 이 초기화돼 있지 않으므로 Mock 기준이어야 한다.
        assertEquals(MockAuthRepository.shared().getCurrentUid(), AuthRepositories.currentUid());
    }
}
