package com.team4.bintime.request;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;
import static com.team4.bintime.request.RequestRepository.*;

public class RequestItemCacheTest {
    private final RequestItemCache cache = new RequestItemCache();

    @Test public void canceledSnapshotBeforeDelayedCreateResponseDoesNotRestorePending() {
        Item canceled = item("request", true, Status.CANCELED);
        cache.replaceSnapshot(true, Map.of(canceled.id, canceled));
        Item callbackResult = cache.applyResponse(item("request", true, Status.PENDING));
        assertSame(canceled, callbackResult);
        assertEquals(Status.CANCELED, cache.get("request").status());
        assertEquals(0, pendingSentCount());

        // 다른 방향의 목록 이벤트로 합쳐져도 오래된 PENDING이 다시 나타나면 안 된다.
        Item received = item("received", false, Status.PENDING);
        cache.replaceSnapshot(false, Map.of(received.id, received));
        assertEquals(Status.CANCELED, cache.get("request").status());
        assertEquals(0, pendingSentCount());
        assertEquals(2, cache.values().size());
    }

    @Test public void createResponseBeforeCancelSnapshotAdvancesNormally() {
        cache.applyResponse(item("request", true, Status.PENDING));
        assertEquals(1, pendingSentCount());
        Item canceled = item("request", true, Status.CANCELED);
        cache.replaceSnapshot(true, Map.of(canceled.id, canceled));
        assertSame(canceled, cache.get("request"));
        assertEquals(0, pendingSentCount());
    }

    @Test public void stalePendingSnapshotCannotUndoSuccessfulCancelResponse() {
        cache.applyResponse(item("request", true, Status.CANCELED));
        Item stale = item("request", true, Status.PENDING);
        cache.replaceSnapshot(true, Map.of(stale.id, stale));
        assertEquals(Status.CANCELED, cache.get("request").status());
        assertEquals(0, pendingSentCount());
    }

    @Test public void acceptedAndRejectedAreAlsoNeverReturnedToPending() {
        for (Status terminal : new Status[]{Status.ACCEPTED, Status.REJECTED}) {
            cache.clear();
            Item settled = item("request", true, terminal);
            cache.replaceSnapshot(true, Map.of(settled.id, settled));
            cache.applyResponse(item("request", true, Status.PENDING));
            assertEquals(terminal, cache.get("request").status());
            assertEquals(0, pendingSentCount());
        }
    }

    @Test public void replacementAndSignOutClearBothDirections() {
        cache.applyResponse(item("sent", true, Status.PENDING));
        cache.applyResponse(item("received", false, Status.PENDING));
        cache.replaceSnapshot(true, Map.of());
        assertNull(cache.get("sent"));
        assertNotNull(cache.get("received"));
        cache.clear();
        assertTrue(cache.values().isEmpty());
        assertNull(cache.get("received"));
    }

    private long pendingSentCount() {
        return cache.values().stream().filter(i -> i.sent && i.status() == Status.PENDING).count();
    }

    private static Item item(String id, boolean sent, Status status) {
        return new Item(id, sent ? "me" : "other", sent ? "other" : "me", "me", "스터디",
                "2026-11-13", 600, 690, status, null, 1000);
    }
}
