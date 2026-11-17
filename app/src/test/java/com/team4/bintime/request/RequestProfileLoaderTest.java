package com.team4.bintime.request;

import com.team4.bintime.profile.ProfileRepository;
import com.team4.bintime.profile.UserProfile;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestProfileLoaderTest {
    private static final class Source implements RequestProfileLoader.Source {
        int calls;
        ProfileRepository.Callback<UserProfile> pending;
        @Override public void load(String uid, ProfileRepository.Callback<UserProfile> callback) { calls++; pending = callback; }
    }

    @Test public void failedReadCanRecoverWithExplicitRetryWithoutRebindLoop() {
        Source source = new Source();
        RequestProfileLoader loader = new RequestProfileLoader(source, () -> {});
        loader.load("peer"); source.pending.onError("offline");
        assertTrue(loader.failed("peer")); assertNull(loader.get("peer"));
        loader.load("peer"); loader.load("peer");
        assertEquals(1, source.calls);
        loader.retry("peer");
        assertFalse(loader.failed("peer")); assertEquals(2, source.calls);
        UserProfile profile = UserProfile.empty(); profile.setNickname("복구된 프로필");
        source.pending.onSuccess(profile);
        loader.load("peer");
        assertEquals(2, source.calls);
        assertEquals("복구된 프로필", loader.get("peer").getNickname());
    }

    @Test public void repeatedBindAndRetryDoNotDuplicateInFlightRead() {
        Source source = new Source();
        RequestProfileLoader loader = new RequestProfileLoader(source, () -> {});
        loader.load("peer"); loader.load("peer"); loader.retry("peer");
        assertEquals(1, source.calls);
    }

    @Test public void missingPublicProfileIsDistinctFromReadFailureAndDoesNotRetry() {
        Source source = new Source();
        RequestProfileLoader loader = new RequestProfileLoader(source, () -> {});
        loader.load("peer"); source.pending.onSuccess(null); loader.load("peer");
        assertTrue(loader.missing("peer")); assertFalse(loader.failed("peer")); assertEquals(1, source.calls);
        loader.retry("peer"); assertEquals(1, source.calls);
    }

    @Test public void retryOfReadFailureCanResolveToMissingWithoutStaleError() {
        Source source = new Source();
        RequestProfileLoader loader = new RequestProfileLoader(source, () -> {});
        loader.load("peer"); source.pending.onError("offline"); loader.retry("peer");
        source.pending.onSuccess(null);
        assertTrue(loader.missing("peer")); assertFalse(loader.failed("peer"));
        loader.load("peer"); loader.retry("peer"); assertEquals(2, source.calls);
    }

    @Test public void callbackAfterViewReleaseCannotRepopulateOrRedraw() {
        Source source = new Source(); int[] changes = {0};
        RequestProfileLoader loader = new RequestProfileLoader(source, () -> changes[0]++);
        loader.load("peer"); loader.close();
        source.pending.onSuccess(UserProfile.empty());
        loader.retry("peer"); loader.load("peer");
        assertNull(loader.get("peer")); assertEquals(0, changes[0]); assertEquals(1, source.calls);
    }
}
