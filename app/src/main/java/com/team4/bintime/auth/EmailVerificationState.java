package com.team4.bintime.auth;

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * 학교 이메일 링크 인증 진행 상태 및 유효성 검증 모델.
 * (BT-003 / Issue #3)
 */
public class EmailVerificationState implements Serializable {

    public enum Status {
        INPUT,            // 학교 이메일 입력 상태 (Figma 100:1411)
        SENT,             // 인증 링크 발송 완료 및 확인 대기 (Figma 235:1961)
        EXPIRED,          // 링크 유효시간 만료 (Figma 268:1969)
        INVALID_LINK,     // 잘못되거나 이미 사용된 링크 (Figma 268:2004)
        DIFFERENT_DEVICE, // 타 기기에서 링크 클릭 시 이메일 재입력 (Figma 268:2039)
        VERIFIED          // 인증 성공 및 로그인 완료
    }

    // 허용 대학교 이메일 도메인 패턴 (.ac.kr, .edu 등)
    private static final Pattern UNIVERSITY_EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.(ac\\.kr|edu)$",
            Pattern.CASE_INSENSITIVE
    );

    /** 인증 링크 재발송 최소 간격. */
    public static final long RESEND_COOLDOWN_MILLIS = 60_000L;

    private Status status;
    private String email;
    private long sentTimestamp;

    public EmailVerificationState() {
        this.status = Status.INPUT;
        this.email = "";
        this.sentTimestamp = 0;
    }

    public EmailVerificationState(@NonNull Status status, String email) {
        this.status = status;
        this.email = email != null ? email : "";
        this.sentTimestamp = System.currentTimeMillis();
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email != null ? email : "";
    }

    public long getSentTimestamp() {
        return sentTimestamp;
    }

    public void setSentTimestamp(long sentTimestamp) {
        this.sentTimestamp = sentTimestamp;
    }

    /**
     * 재발송까지 남은 시간(ms). 발송 기록이 없거나 쿨다운이 지났으면 0.
     * 서버(Firebase) 발송 제한과 별도로 UI 에서 연속 재발송을 막는다. (BT-013)
     */
    public long remainingResendCooldownMillis(long nowMillis) {
        if (sentTimestamp <= 0) return 0;
        return Math.max(0, sentTimestamp + RESEND_COOLDOWN_MILLIS - nowMillis);
    }

    /** 남은 쿨다운을 초 단위로 올림. 화면 안내 문구용. */
    public int remainingResendCooldownSeconds(long nowMillis) {
        long remaining = remainingResendCooldownMillis(nowMillis);
        return (int) ((remaining + 999) / 1000);
    }

    /**
     * 학교 이메일 형식 유효성 검증.
     * .ac.kr 또는 .edu 도메인을 검증합니다.
     */
    public static boolean isValidUniversityEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        return UNIVERSITY_EMAIL_PATTERN.matcher(email.trim()).matches();
    }
}
