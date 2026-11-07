package com.team4.bintime.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MockAuthRepositoryTest {

    private MockAuthRepository repository;

    @Before
    public void setUp() {
        repository = new MockAuthRepository();
    }

    @Test
    public void universityEmailDomainValidation() {
        // 허용 도메인 (.ac.kr, .edu)
        assertTrue(EmailVerificationState.isValidUniversityEmail("yu.bin@kookmin.ac.kr"));
        assertTrue(EmailVerificationState.isValidUniversityEmail("student@snu.ac.kr"));
        assertTrue(EmailVerificationState.isValidUniversityEmail("test.user@stanford.edu"));

        // 거부 도메인 (일반 포털)
        assertFalse(EmailVerificationState.isValidUniversityEmail("user@gmail.com"));
        assertFalse(EmailVerificationState.isValidUniversityEmail("user@naver.com"));
        assertFalse(EmailVerificationState.isValidUniversityEmail("user@daum.net"));

        // 유효하지 않은 형식
        assertFalse(EmailVerificationState.isValidUniversityEmail(""));
        assertFalse(EmailVerificationState.isValidUniversityEmail(null));
        assertFalse(EmailVerificationState.isValidUniversityEmail("invalid-email-format"));
        assertFalse(EmailVerificationState.isValidUniversityEmail("@kookmin.ac.kr"));
    }

    @Test
    public void sendSignInLink_success_forUniversityEmail() {
        AtomicBoolean successCalled = new AtomicBoolean(false);

        repository.sendSignInLink("yu.bin@kookmin.ac.kr", new AuthRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                successCalled.set(true);
            }

            @Override
            public void onError(String message) {}
        });

        assertTrue(successCalled.get());
        assertEquals("yu.bin@kookmin.ac.kr", repository.getPendingEmail());
    }

    @Test
    public void sendSignInLink_fails_forNonUniversityEmail() {
        AtomicBoolean errorCalled = new AtomicBoolean(false);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        repository.sendSignInLink("hacker@gmail.com", new AuthRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {}

            @Override
            public void onError(String message) {
                errorCalled.set(true);
                errorMessage.set(message);
            }
        });

        assertTrue(errorCalled.get());
        assertNotNull(errorMessage.get());
        assertNull(repository.getPendingEmail());
    }

    @Test
    public void verifySignInLink_success_andSignOut() {
        AtomicReference<String> uidRef = new AtomicReference<>();

        repository.verifySignInLink("yu.bin@kookmin.ac.kr", "mock_link", new AuthRepository.Callback<String>() {
            @Override
            public void onSuccess(String uid) {
                uidRef.set(uid);
            }

            @Override
            public void onError(String message) {}
        });

        assertNotNull(uidRef.get());
        assertTrue(repository.isSignedIn());
        assertEquals(uidRef.get(), repository.getCurrentUid());
        assertTrue(repository.isRegisteredEmail("yu.bin@kookmin.ac.kr"));

        // 로그아웃 검증
        repository.signOut();
        assertFalse(repository.isSignedIn());
        assertNull(repository.getCurrentUid());
    }

    @Test
    public void emailVerificationState_transitions() {
        EmailVerificationState state = new EmailVerificationState();
        assertEquals(EmailVerificationState.Status.INPUT, state.getStatus());

        state.setStatus(EmailVerificationState.Status.SENT);
        state.setEmail("yu.bin@kookmin.ac.kr");
        assertEquals(EmailVerificationState.Status.SENT, state.getStatus());
        assertEquals("yu.bin@kookmin.ac.kr", state.getEmail());

        state.setStatus(EmailVerificationState.Status.EXPIRED);
        assertEquals(EmailVerificationState.Status.EXPIRED, state.getStatus());

        state.setStatus(EmailVerificationState.Status.INVALID_LINK);
        assertEquals(EmailVerificationState.Status.INVALID_LINK, state.getStatus());

        state.setStatus(EmailVerificationState.Status.DIFFERENT_DEVICE);
        assertEquals(EmailVerificationState.Status.DIFFERENT_DEVICE, state.getStatus());

        state.setStatus(EmailVerificationState.Status.VERIFIED);
        assertEquals(EmailVerificationState.Status.VERIFIED, state.getStatus());
    }
}
