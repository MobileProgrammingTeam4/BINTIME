package com.team4.bintime.auth;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** BT-013: 인증 링크 재발송 쿨다운. */
public class EmailVerificationStateTest {

    @Test
    public void neverSent_hasNoCooldown() {
        EmailVerificationState state = new EmailVerificationState();
        assertEquals(0, state.remainingResendCooldownMillis(1_000_000L));
        assertEquals(0, state.remainingResendCooldownSeconds(1_000_000L));
    }

    @Test
    public void justSent_blocksForFullCooldown() {
        EmailVerificationState state = new EmailVerificationState();
        state.setSentTimestamp(1_000_000L);
        assertEquals(EmailVerificationState.RESEND_COOLDOWN_MILLIS, state.remainingResendCooldownMillis(1_000_000L));
        assertEquals(60, state.remainingResendCooldownSeconds(1_000_000L));
    }

    @Test
    public void partialSecond_roundsUp() {
        EmailVerificationState state = new EmailVerificationState();
        state.setSentTimestamp(1_000_000L);
        assertEquals(1, state.remainingResendCooldownSeconds(1_000_000L + 59_001L));
    }

    @Test
    public void afterCooldown_allowsResend() {
        EmailVerificationState state = new EmailVerificationState();
        state.setSentTimestamp(1_000_000L);
        assertEquals(0, state.remainingResendCooldownMillis(1_000_000L + 60_000L));
        assertEquals(0, state.remainingResendCooldownSeconds(1_000_000L + 120_000L));
    }
}
