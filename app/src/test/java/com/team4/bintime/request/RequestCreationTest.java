package com.team4.bintime.request;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.team4.bintime.request.RequestRepository.*;

public class RequestCreationTest {
    private Item result;
    private String error;
    private final Callback callback = new Callback() {
        @Override public void onSuccess(Item item) { result = item; }
        @Override public void onError(String message) { error = message; }
    };

    @Test public void creationUsesAuthOwnerAndIndependentTimeSnapshot() {
        MockRequestRepository repo = new MockRequestRepository("sender", false);
        repo.create(RequestDraft.mock("receiver", "산책", "2026-11-12", 600, 690), callback);
        assertNull(error);
        assertEquals("sender", result.fromUid);
        assertEquals("receiver", result.toUid);
        assertEquals("산책", result.purpose);
        assertEquals(90, result.overlapMinutes);
        assertEquals(Status.PENDING, result.status());
        assertEquals(1, repo.list(Tab.SENT).size());
        assertTrue(repo.respond(result.id, Status.CANCELED));
        assertEquals("2026-11-12", result.meetingDate);
        assertEquals(600, result.startMinute);
        assertEquals(690, result.endMinute);
    }

    @Test public void repeatedUiIntentsUseOnePendingRequest() {
        MockRequestRepository repo = new MockRequestRepository("sender", false);
        RequestDraft draft = RequestDraft.mock("receiver", "스터디", "2026-11-12", 600, 720);
        repo.create(draft, callback);
        String id = result.id;
        repo.create(draft, callback);
        assertEquals(id, result.id);
        repo.create(RequestDraft.mock("receiver", "스터디", "2026-11-13", 900, 1020), callback);
        assertEquals(id, result.id);
        assertEquals(1, repo.list(Tab.SENT).size());
    }

    @Test public void selfInvalidTimesAndRealCandidateAreRejectedByMock() {
        MockRequestRepository repo = new MockRequestRepository("sender", false);
        repo.create(RequestDraft.mock("sender", "스터디", "2026-11-12", 600, 720), callback);
        assertNotNull(error);
        error = null;
        repo.create(RequestDraft.mock("receiver", "스터디", "2026-11-12", 600, 659), callback);
        assertNotNull(error);
        error = null;
        repo.create(RequestDraft.verified("candidate", "receiver", "attempt"), callback);
        assertNotNull(error);
        assertTrue(repo.list(Tab.SENT).isEmpty());
    }

    @Test public void observerRemovedAfterLifecycleStopDoesNotReceiveChanges() {
        MockRequestRepository repo = new MockRequestRepository("sender", false);
        int[] calls = {0};
        Registration registration = repo.observe(() -> calls[0]++);
        repo.create(RequestDraft.mock("receiver", "스터디", "2026-11-12", 600, 720), callback);
        assertEquals(2, calls[0]);
        registration.remove();
        repo.respond(result.id, Status.CANCELED);
        assertEquals(2, calls[0]);
    }
}
