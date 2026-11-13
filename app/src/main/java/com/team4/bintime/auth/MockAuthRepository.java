package com.team4.bintime.auth;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Sprint 1 UI 목업용 가상 인증 저장소.
 * 6자리 OTP 없이 이메일 링크 발송 및 상태 전환 흐름을 지원합니다.
 *
 * 실제 메일을 보내지 않고 메모리에 "발송한 링크"를 하나 보관한 뒤, 인증 시 다음을 검증한다.
 * - 링크를 발송한 적이 있는지
 * - 마지막으로 발송한 링크와 같은지 (재발송하면 이전 링크는 무효)
 * - 유효 시간({@link #LINK_TTL_MILLIS})이 지나지 않았는지
 * - 이미 사용한 링크가 아닌지
 * - 링크를 요청한 이메일과 입력한 이메일이 같은지
 *
 * 실제 Firebase Auth 이메일 링크 발송·검증과 앱 링크 복귀는 BT-014에서 같은 {@link AuthRepository} 구현으로 교체한다.
 * 유효 시간 값은 시연용이며 Firebase 정책 값이 아니다.
 */
public class MockAuthRepository implements AuthRepository {

    /** 시연용 링크 유효 시간 (1시간). */
    public static final long LINK_TTL_MILLIS = 60L * 60L * 1000L;
    static final String LINK_PREFIX = "https://bintime.mock/auth?oobCode=";

    /** 테스트에서 만료를 검증하기 위한 시간 공급자. */
    public interface TimeSource {
        long now();
    }

    private static MockAuthRepository shared;

    /** 인증 화면·프로필 생성·로그아웃이 같은 인증 상태를 보도록 앱 전역에서 공유한다. */
    public static synchronized MockAuthRepository shared() {
        if (shared == null) shared = new MockAuthRepository();
        return shared;
    }

    private final TimeSource timeSource;
    private final Set<String> registeredEmails = new HashSet<>();
    private final Set<String> usedLinks = new HashSet<>();

    private String currentUid = null;
    private String pendingEmail = null;
    private String pendingLink = null;
    private long linkSentAt = 0L;

    public MockAuthRepository() {
        this(System::currentTimeMillis);
    }

    public MockAuthRepository(TimeSource timeSource) {
        this.timeSource = timeSource;
        // 기존 가입된 계정 예시 (1이메일 1계정 정책 확인용)
        registeredEmails.add("yu.bin@kookmin.ac.kr");
    }

    @Override
    public void sendSignInLink(String email, Callback<Void> callback) {
        if (!EmailVerificationState.isValidUniversityEmail(email)) {
            fail(callback, Failure.INVALID_EMAIL, "허용된 대학교 이메일 도메인이 아닙니다.");
            return;
        }

        // 새 링크를 발급하면 이전에 보낸 링크는 더 이상 유효하지 않다.
        this.pendingEmail = normalize(email);
        this.pendingLink = LINK_PREFIX + UUID.randomUUID();
        this.linkSentAt = timeSource.now();
        if (callback != null) {
            callback.onSuccess(null);
        }
    }

    @Override
    public void resendSignInLink(String email, Callback<Void> callback) {
        sendSignInLink(email, callback);
    }

    @Override
    public void verifySignInLink(String email, String link, Callback<String> callback) {
        if (!EmailVerificationState.isValidUniversityEmail(email)) {
            fail(callback, Failure.INVALID_EMAIL, "인증 링크를 받은 학교 이메일을 입력해 주세요.");
            return;
        }
        if (link != null && usedLinks.contains(link)) {
            fail(callback, Failure.LINK_ALREADY_USED, "이미 사용된 인증 링크예요.");
            return;
        }
        if (pendingLink == null) {
            fail(callback, Failure.LINK_NOT_SENT, "먼저 인증 링크를 발송해 주세요.");
            return;
        }
        if (!pendingLink.equals(link)) {
            fail(callback, Failure.INVALID_LINK, "잘못된 인증 링크예요.");
            return;
        }
        if (timeSource.now() - linkSentAt > LINK_TTL_MILLIS) {
            fail(callback, Failure.LINK_EXPIRED, "인증 링크가 만료되었어요.");
            return;
        }
        String normalized = normalize(email);
        if (!normalized.equals(pendingEmail)) {
            // 링크는 소모하지 않는다. 올바른 이메일을 다시 입력하면 같은 링크로 인증할 수 있다.
            fail(callback, Failure.EMAIL_MISMATCH, "인증 링크를 받은 이메일과 일치하지 않아요.");
            return;
        }

        usedLinks.add(link);
        pendingLink = null;
        pendingEmail = null;
        registeredEmails.add(normalized);
        currentUid = "mock_uid_" + Integer.toHexString(normalized.hashCode());

        if (callback != null) {
            callback.onSuccess(currentUid);
        }
    }

    @Override
    public boolean isSignedIn() {
        return currentUid != null;
    }

    @Override
    public String getCurrentUid() {
        return currentUid;
    }

    /**
     * Mock 전용 프로세스 복원. MainActivity의 저장 상태에서 복원하며 Firebase 세션에는 사용하지 않는다.
     * 이미 다른 계정으로 로그인했다면 이전 저장 상태로 계정을 바꾸지 않는다.
     */
    public boolean restoreSession(String uid) {
        if (uid == null || uid.isBlank()) return false;
        if (currentUid != null) return currentUid.equals(uid);
        currentUid = uid;
        return true;
    }

    @Override
    public void signOut() {
        currentUid = null;
        pendingEmail = null;
        pendingLink = null;
    }

    public String getPendingEmail() {
        return pendingEmail;
    }

    public boolean isRegisteredEmail(String email) {
        return email != null && registeredEmails.contains(normalize(email));
    }

    /** 목업 시연용: 메일함 대신 마지막으로 발송한 링크를 돌려준다. 실제 구현에는 없다. */
    public String getIssuedLinkForDemo() {
        return pendingLink;
    }

    /** 목업 시연용: 발송한 링크의 유효 시간이 지난 상태로 만든다. 실제 구현에는 없다. */
    public void expirePendingLinkForDemo() {
        linkSentAt = timeSource.now() - LINK_TTL_MILLIS - 1L;
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> void fail(Callback<T> callback, Failure failure, String message) {
        if (callback != null) {
            callback.onFailure(failure, message);
        }
    }
}
