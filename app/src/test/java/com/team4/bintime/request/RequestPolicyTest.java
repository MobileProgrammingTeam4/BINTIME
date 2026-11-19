package com.team4.bintime.request;

import org.junit.Test;
import static org.junit.Assert.*;

public class RequestPolicyTest {
    private RequestRepository.Item item;
    private String error;
    private final RequestRepository.Callback callback = new RequestRepository.Callback() {
        @Override public void onSuccess(RequestRepository.Item value) { item = value; }
        @Override public void onError(String message) { error = message; }
    };
    private RequestDraft draft(String uid) { return RequestDraft.mock(uid, "스터디", "2030-11-17", 600, 690); }

    @Test public void sentLimitAndSameUuidRetry() {
        MockRequestRepository repo = new MockRequestRepository("me", false);
        RequestDraft original = draft("a");
        repo.create(original, callback); String id = item.id;
        repo.create(draft("b"), callback); repo.create(draft("c"), callback);
        repo.create(draft("d"), callback); assertNotNull(error);
        assertEquals(3, repo.list(RequestRepository.Tab.SENT).size());
        error = null; repo.create(original, callback); assertNull(error); assertEquals(id, item.id);
    }
    @Test public void cancellationBlocksAllTargetsForExactlyOneMinuteAndRetryDoesNotExtend() {
        long[] clock = {1_000};
        MockRequestRepository repo = new MockRequestRepository("me", false, () -> clock[0]);
        RequestDraft original = draft("a"); repo.create(original, callback); String id = item.id;
        repo.cancel(id, callback); clock[0] += 30_000; repo.cancel(id, callback);
        repo.create(draft("b"), callback); assertNotNull(error);
        error = null; repo.create(original, callback); assertNull(error);
        assertEquals(RequestRepository.Status.CANCELED, item.status());
        clock[0] = 60_999; repo.create(draft("b"), callback); assertNotNull(error);
        error = null; clock[0]++; repo.create(draft("b"), callback); assertNull(error);
        assertEquals("b", item.toUid);
    }
    @Test public void receivedRequestPreventsReverseSubmission() {
        MockRequestRepository repo = new MockRequestRepository("me", true);
        repo.create(draft("u1"), callback);
        assertTrue(error.contains("받은 요청"));
    }
}
