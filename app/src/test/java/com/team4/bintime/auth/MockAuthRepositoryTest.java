package com.team4.bintime.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.atomic.AtomicLong;

public class MockAuthRepositoryTest {

    private static final String EMAIL = "yu.bin@kookmin.ac.kr";

    /** 콜백 결과를 기록하는 테스트용 콜백. */
    private static final class Recorder<T> implements AuthRepository.Callback<T> {
        boolean success;
        T result;
        AuthRepository.Failure failure;
        String errorMessage;

        @Override public void onSuccess(T result) {
            success = true;
            this.result = result;
        }

        @Override public void onError(String message) {
            errorMessage = message;
        }

        @Override public void onFailure(AuthRepository.Failure failure, String message) {
            this.failure = failure;
            this.errorMessage = message;
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private MockAuthRepository repository;

    @Before
    public void setUp() {
        repository = new MockAuthRepository(now::get);
    }

    @Test public void restoreMockSessionKeepsAuthAndRequestOwnerTogether() {
        assertFalse(repository.restoreSession(null));
        assertFalse(repository.restoreSession(" "));
        assertTrue(repository.restoreSession("mock_uid_saved"));
        assertTrue(repository.isSignedIn());
        assertEquals("mock_uid_saved", repository.getCurrentUid());
        assertTrue(repository.restoreSession("mock_uid_saved"));
        assertFalse(repository.restoreSession("mock_uid_other"));
        assertEquals("mock_uid_saved", repository.getCurrentUid());
        repository.signOut();
        assertFalse(repository.isSignedIn());
    }

    /** BT-030: 기본 세션 확인은 로그인 여부를 따른다(Mock 은 서버가 없으므로 만료되지 않음). */
    @Test public void checkSession_followsSignInState() {
        Recorder<AuthRepository.SessionState> signedOut = new Recorder<>();
        repository.checkSession(signedOut);
        assertEquals(AuthRepository.SessionState.EXPIRED, signedOut.result);

        repository.restoreSession("mock_uid_saved");
        Recorder<AuthRepository.SessionState> signedIn = new Recorder<>();
        repository.checkSession(signedIn);
        assertEquals(AuthRepository.SessionState.VALID, signedIn.result);
    }

    /** BT-030: 링크를 보낸 뒤 앱을 다시 열면 보낸 이메일로 '메일 확인' 화면을 복원하고, 로그아웃하면 지운다. */
    @Test public void pendingEmail_keptUntilSignOut() {
        sendLink(EMAIL);
        assertEquals(EMAIL, repository.getPendingEmail());
        repository.signOut();
        assertNull(repository.getPendingEmail());
    }

    private String sendLink(String email) {
        Recorder<Void> sent = new Recorder<>();
        repository.sendSignInLink(email, sent);
        assertTrue(sent.success);
        return repository.getIssuedLinkForDemo();
    }

    private Recorder<String> verify(String email, String link) {
        Recorder<String> recorder = new Recorder<>();
        repository.verifySignInLink(email, link, recorder);
        return recorder;
    }

    private void assertFailure(Recorder<String> recorder, AuthRepository.Failure expected) {
        assertFalse(recorder.success);
        assertEquals(expected, recorder.failure);
        assertNotNull(recorder.errorMessage);
        assertFalse(repository.isSignedIn());
        assertNull(repository.getCurrentUid());
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
        String link = sendLink(EMAIL);

        assertEquals(EMAIL, repository.getPendingEmail());
        assertNotNull(link);
        assertFalse(repository.isSignedIn());
    }

    @Test
    public void sendSignInLink_fails_forNonUniversityEmail() {
        Recorder<Void> recorder = new Recorder<>();
        repository.sendSignInLink("hacker@gmail.com", recorder);

        assertFalse(recorder.success);
        assertEquals(AuthRepository.Failure.INVALID_EMAIL, recorder.failure);
        assertNull(repository.getPendingEmail());
        assertNull(repository.getIssuedLinkForDemo());
    }

    @Test
    public void verify_success_withIssuedLinkAndSameEmail_thenSignOut() {
        String link = sendLink(EMAIL);

        Recorder<String> recorder = verify(EMAIL, link);

        assertTrue(recorder.success);
        assertNotNull(recorder.result);
        assertTrue(repository.isSignedIn());
        assertEquals(recorder.result, repository.getCurrentUid());
        assertTrue(repository.isRegisteredEmail(EMAIL));
        assertNull(repository.getPendingEmail());

        repository.signOut();
        assertFalse(repository.isSignedIn());
        assertNull(repository.getCurrentUid());
    }

    @Test
    public void verify_fails_whenLinkWasNeverSent() {
        assertFailure(verify(EMAIL, MockAuthRepository.LINK_PREFIX + "anything"),
                AuthRepository.Failure.LINK_NOT_SENT);
    }

    @Test
    public void verify_fails_whenEmailDiffersFromRequestedEmail() {
        String link = sendLink(EMAIL);

        assertFailure(verify("other.user@kookmin.ac.kr", link), AuthRepository.Failure.EMAIL_MISMATCH);
        // 불일치로 링크가 소모되지 않아, 올바른 이메일로는 계속 인증할 수 있다.
        assertEquals(link, repository.getIssuedLinkForDemo());
    }

    @Test
    public void verify_fails_forInvalidLink() {
        sendLink(EMAIL);

        assertFailure(verify(EMAIL, MockAuthRepository.LINK_PREFIX + "invalid"),
                AuthRepository.Failure.INVALID_LINK);
        assertFailure(verify(EMAIL, null), AuthRepository.Failure.INVALID_LINK);
        assertFailure(verify(EMAIL, ""), AuthRepository.Failure.INVALID_LINK);
    }

    @Test
    public void verify_fails_forExpiredLink() {
        String link = sendLink(EMAIL);
        now.addAndGet(MockAuthRepository.LINK_TTL_MILLIS + 1);

        assertFailure(verify(EMAIL, link), AuthRepository.Failure.LINK_EXPIRED);
    }

    @Test
    public void verify_succeeds_exactlyAtExpiryBoundary() {
        String link = sendLink(EMAIL);
        now.addAndGet(MockAuthRepository.LINK_TTL_MILLIS);

        assertTrue(verify(EMAIL, link).success);
    }

    @Test
    public void verify_fails_forAlreadyUsedLink() {
        String link = sendLink(EMAIL);
        assertTrue(verify(EMAIL, link).success);
        repository.signOut();

        assertFailure(verify(EMAIL, link), AuthRepository.Failure.LINK_ALREADY_USED);
    }

    @Test
    public void resend_invalidatesPreviousLink() {
        String oldLink = sendLink(EMAIL);
        Recorder<Void> resent = new Recorder<>();
        repository.resendSignInLink(EMAIL, resent);
        String newLink = repository.getIssuedLinkForDemo();

        assertTrue(resent.success);
        assertFalse(oldLink.equals(newLink));
        assertFailure(verify(EMAIL, oldLink), AuthRepository.Failure.INVALID_LINK);
        assertTrue(verify(EMAIL, newLink).success);
    }

    @Test
    public void differentDevice_reenterEmail_thenVerify() {
        // 기기 A에서 링크 요청 → 기기 B에서 링크를 열어 이메일 정보 없이 재입력
        String linkOpenedOnOtherDevice = sendLink(EMAIL);

        // 잘못된 이메일 재입력 → 실패
        assertFailure(verify("wrong@kookmin.ac.kr", linkOpenedOnOtherDevice),
                AuthRepository.Failure.EMAIL_MISMATCH);
        // 대소문자·공백이 달라도 같은 이메일이면 성공
        Recorder<String> recorder = verify("  YU.BIN@kookmin.ac.kr ", linkOpenedOnOtherDevice);
        assertTrue(recorder.success);
        assertTrue(repository.isSignedIn());
    }

    @Test
    public void verify_fails_forNonUniversityEmail() {
        String link = sendLink(EMAIL);

        assertFailure(verify("user@gmail.com", link), AuthRepository.Failure.INVALID_EMAIL);
        assertFailure(verify("", link), AuthRepository.Failure.INVALID_EMAIL);
    }

    @Test
    public void demoExpire_makesIssuedLinkExpired() {
        String link = sendLink(EMAIL);
        repository.expirePendingLinkForDemo();

        assertFailure(verify(EMAIL, link), AuthRepository.Failure.LINK_EXPIRED);
    }

    @Test
    public void callbackWithoutOnFailure_fallsBackToOnError() {
        StringBuilder message = new StringBuilder();
        repository.verifySignInLink(EMAIL, "x", new AuthRepository.Callback<String>() {
            @Override public void onSuccess(String result) {}
            @Override public void onError(String m) { message.append(m); }
        });

        assertTrue(message.length() > 0);
    }

    @Test
    public void sharedInstance_isSameAcrossScreens() {
        assertTrue(MockAuthRepository.shared() == MockAuthRepository.shared());
    }

    @Test
    public void emailVerificationState_transitions() {
        EmailVerificationState state = new EmailVerificationState();
        assertEquals(EmailVerificationState.Status.INPUT, state.getStatus());

        state.setStatus(EmailVerificationState.Status.SENT);
        state.setEmail(EMAIL);
        assertEquals(EmailVerificationState.Status.SENT, state.getStatus());
        assertEquals(EMAIL, state.getEmail());

        state.setStatus(EmailVerificationState.Status.EXPIRED);
        assertEquals(EmailVerificationState.Status.EXPIRED, state.getStatus());

        state.setStatus(EmailVerificationState.Status.INVALID_LINK);
        assertEquals(EmailVerificationState.Status.INVALID_LINK, state.getStatus());

        state.setStatus(EmailVerificationState.Status.DIFFERENT_DEVICE);
        assertEquals(EmailVerificationState.Status.DIFFERENT_DEVICE, state.getStatus());

        state.setStatus(EmailVerificationState.Status.VERIFIED);
        assertEquals(EmailVerificationState.Status.VERIFIED, state.getStatus());
    }

    /** 화면 회전 시 Bundle 에 저장되는 인증 화면 상태가 직렬화 후에도 유지되는지 확인. */
    @Test
    public void emailVerificationState_survivesSerialization() throws Exception {
        EmailVerificationState state = new EmailVerificationState(EmailVerificationState.Status.SENT, EMAIL);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(state);
        }
        EmailVerificationState restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (EmailVerificationState) in.readObject();
        }

        assertEquals(EmailVerificationState.Status.SENT, restored.getStatus());
        assertEquals(EMAIL, restored.getEmail());
    }
}
