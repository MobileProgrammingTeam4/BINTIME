package com.team4.bintime.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** BT-045 탈퇴 결과는 한 번만 처리하고, 진행 중 표시는 회전 뒤에도 다시 보인다. */
public class AccountDeletionViewModelTest {

    @Test
    public void finishedStateIsConsumedOnce() {
        for (AccountDeletionViewModel.Status status : new AccountDeletionViewModel.Status[]{
                AccountDeletionViewModel.Status.DONE, AccountDeletionViewModel.Status.FAILED}) {
            AccountDeletionViewModel.State state = new AccountDeletionViewModel.State(status, "실패");
            assertTrue(state.consume());
            assertFalse(state.consume()); // 회전 후 다시 관찰돼도 로그아웃·토스트를 두 번 하지 않는다
        }
    }

    @Test
    public void runningStateIsShownAgainAfterRecreation() {
        AccountDeletionViewModel.State running = new AccountDeletionViewModel.State(AccountDeletionViewModel.Status.RUNNING, null);
        assertTrue(running.consume());
        assertTrue(running.consume());
    }
}
