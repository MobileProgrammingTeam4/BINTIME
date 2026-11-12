package com.team4.bintime.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * BT-013 리뷰 반영: 회전 뒤 같은 검증 결과를 두 번 처리하지 않는다.
 * BT-014(#83 리뷰 반영): 프로필 완료 여부도 결과에 담겨, 회전 뒤 새 Activity 가 다시 조회하지 않고 이동한다.
 */
public class EmailAuthViewModelResultTest {

    @Test
    public void success_isConsumedOnlyOnce() {
        EmailAuthViewModel.Result result = EmailAuthViewModel.Result.success("uid1", "a@kookmin.ac.kr", true);
        assertTrue(result.isSuccess());
        assertEquals("uid1", result.uid);
        assertEquals("a@kookmin.ac.kr", result.email);
        assertEquals(Boolean.TRUE, result.profileCompleted);
        assertTrue(result.consume());
        assertFalse(result.consume());
    }

    @Test
    public void success_withoutProfile_routesToSignup() {
        EmailAuthViewModel.Result result = EmailAuthViewModel.Result.success("uid1", "a@kookmin.ac.kr", false);
        assertTrue(result.isSuccess());
        assertEquals(Boolean.FALSE, result.profileCompleted);
    }

    @Test
    public void profileCheckFailed_isSignedInButUnknownProfile() {
        // 로그인은 끝났으므로 성공으로 보되, 완료 여부는 모름(null) → 온보딩에서 재확인한다.
        EmailAuthViewModel.Result result =
                EmailAuthViewModel.Result.profileCheckFailed("uid1", "a@kookmin.ac.kr", "네트워크 연결을 확인해 주세요.");
        assertTrue(result.isSuccess());
        assertNull(result.profileCompleted);
        assertNull(result.failure);
        assertEquals("네트워크 연결을 확인해 주세요.", result.message);
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
        assertNull(result.profileCompleted);
        assertTrue(result.consume());
        assertFalse(result.consume());
    }

    @Test
    public void currentUid_withoutFirebase_fallsBackToMock() {
        // JVM 테스트에는 FirebaseApp 이 초기화돼 있지 않으므로 Mock 기준이어야 한다.
        assertEquals(MockAuthRepository.shared().getCurrentUid(), AuthRepositories.currentUid());
    }
}
