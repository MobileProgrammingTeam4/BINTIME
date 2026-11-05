package com.team4.bintime.request;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import static org.junit.Assert.*;
import static com.team4.bintime.request.MockRequestRepository.*;

public class MockRequestRepositoryTest {
    @Test public void accept_movesToCompletedAndCancelsConflictingSentRequest() {
        MockRequestRepository repo = new MockRequestRepository();
        Item original = repo.find("received-1");
        String date = original.meetingDate;
        assertTrue(repo.respond(original.id, Status.ACCEPTED));
        assertTrue(repo.list(Tab.RECEIVED).isEmpty());
        assertTrue(repo.list(Tab.SENT).isEmpty());
        assertEquals(Status.CANCELED, repo.find("sent-1").status());
        assertEquals(2, repo.list(Tab.COMPLETED).size());
        assertEquals(MatchStatus.ACTIVE, original.matchStatus());
        assertEquals(MatchStatus.ENDED, repo.find("completed-1").matchStatus());
        assertEquals(date, original.meetingDate);
        assertEquals(840, original.startMinute);
        assertEquals(930, original.endMinute);
    }

    @Test public void reject_doesNotChangeSentOrCompleted() {
        MockRequestRepository repo = new MockRequestRepository();
        assertTrue(repo.respond("received-1", Status.REJECTED));
        assertTrue(repo.list(Tab.RECEIVED).isEmpty());
        assertEquals(1, repo.list(Tab.SENT).size());
        assertEquals(1, repo.list(Tab.COMPLETED).size());
        assertEquals(Status.REJECTED, repo.find("received-1").status());
    }

    @Test public void cancel_isIdempotentAndRejectsInvalidDirectionOrId() {
        MockRequestRepository repo = new MockRequestRepository();
        assertFalse(repo.respond("sent-1", Status.ACCEPTED));
        assertFalse(repo.respond("received-1", Status.CANCELED));
        assertFalse(repo.respond("missing", Status.REJECTED));
        assertFalse(repo.respond("sent-1", null));
        assertTrue(repo.respond("sent-1", Status.CANCELED));
        assertFalse(repo.respond("sent-1", Status.CANCELED));
        assertFalse(repo.respond("completed-1", Status.CANCELED));
        assertTrue(repo.list(Tab.SENT).isEmpty());
    }

    @Test public void restoredSession_preservesStatusesAndSnapshots() throws Exception {
        MockRequestRepository repo = new MockRequestRepository();
        repo.respond("received-1", Status.ACCEPTED);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(repo); }
        MockRequestRepository restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (MockRequestRepository) input.readObject();
        }
        assertEquals(repo.find("received-1").meetingDate, restored.find("received-1").meetingDate);
        assertEquals(2, restored.list(Tab.COMPLETED).size());
        assertTrue(restored.list(Tab.SENT).isEmpty());
        assertFalse(restored.respond("received-1", Status.ACCEPTED));
    }
}
