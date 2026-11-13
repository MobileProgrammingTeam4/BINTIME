package com.team4.bintime.matching;

import android.content.Context;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.auth.MockAuthRepository;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.request.RequestDraft;
import com.team4.bintime.request.RequestRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

/** Pixel 10 실제 View 경합 회귀. FirebaseApp 제거 후 로컬 지연 저장소만 사용한다. */
@RunWith(AndroidJUnit4.class)
public class RequestButtonDeviceTest {
    private ActivityScenario<ProfileDetailActivity> scenario;
    private final DelayedRepository repository = new DelayedRepository();
    private final MatchCandidate a = MockMatchRepository.find("u2");
    private final MatchCandidate b = MockMatchRepository.find("u3");

    @Before public void launchWithoutFirebase() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (FirebaseApp app : new ArrayList<>(FirebaseApp.getApps(context))) app.delete();
        assertFalse(AuthRepositories.isFirebaseEnabled(context));
        MockAuthRepository auth = MockAuthRepository.shared();
        auth.sendSignInLink("request.qa@kookmin.ac.kr", null);
        auth.verifySignInLink("request.qa@kookmin.ac.kr", auth.getIssuedLinkForDemo(), null);
        scenario = ActivityScenario.launch(ProfileDetailActivity.intent(context, a.uid, Collections.emptySet()));
    }

    @After public void close() {
        if (scenario != null) scenario.close();
        MockAuthRepository.shared().signOut();
    }

    @Test public void staleSuccessDoesNotReplaceAnotherCandidatesButtonOrClickTarget() {
        scenario.onActivity(activity -> {
            TextView button = button(activity);
            bind(button, a); button.performClick();
            bind(button, b);
            repository.succeed(0, RequestRepository.Status.PENDING);
            assertTrue(button.isEnabled());
            assertEquals("매칭 신청", button.getText().toString());
            noFeedback(activity);
            button.performClick();
            assertEquals(b.uid, repository.calls.get(1).draft.candidateId);
        });
    }

    @Test public void staleErrorCannotUnlockAnotherCandidatesActiveSubmission() {
        scenario.onActivity(activity -> {
            TextView button = button(activity);
            bind(button, a); button.performClick();
            bind(button, b); button.performClick();
            repository.fail(0);
            assertFalse(button.isEnabled());
            assertEquals("신청 중…", button.getText().toString());
            noFeedback(activity);
        });
    }

    @Test public void sameCandidateRebindingRefreshesPendingStateWithoutOldFeedback() {
        scenario.onActivity(activity -> {
            TextView button = button(activity);
            bind(button, a); button.performClick();
            bind(button, a);
            repository.succeed(0, RequestRepository.Status.PENDING);
            assertFalse(button.isEnabled());
            assertEquals("신청 완료", button.getText().toString());
            noFeedback(activity);
        });
    }

    @Test public void sameCandidateRebindingUnlocksAfterOldAttemptFails() {
        scenario.onActivity(activity -> {
            TextView button = button(activity);
            bind(button, a); button.performClick();
            bind(button, a);
            repository.fail(0);
            assertTrue(button.isEnabled());
            assertEquals("매칭 신청", button.getText().toString());
            noFeedback(activity);
        });
    }

    @Test public void pendingResponseShowsSuccessAndLocksButton() {
        scenario.onActivity(activity -> {
            TextView button = button(activity);
            bind(button, a); button.performClick();
            repository.succeed(0, RequestRepository.Status.PENDING);
            activity.getSupportFragmentManager().executePendingTransactions();
            assertNotNull(activity.getSupportFragmentManager().findFragmentByTag("feedback"));
            assertFalse(button.isEnabled());
        });
    }

    @Test public void canceledResponseDoesNotShowCreationSuccessAndNextAttemptHasNewUuid() {
        terminalResponse(RequestRepository.Status.CANCELED);
    }

    @Test public void rejectedResponseDoesNotShowCreationSuccessAndNextAttemptHasNewUuid() {
        terminalResponse(RequestRepository.Status.REJECTED);
    }

    @Test public void acceptedResponseDoesNotShowNewPendingSuccess() {
        terminalResponse(RequestRepository.Status.ACCEPTED);
    }

    private void terminalResponse(RequestRepository.Status status) {
        scenario.onActivity(activity -> {
            TextView button = button(activity);
            bind(button, a); button.performClick();
            repository.succeed(0, status);
            noFeedback(activity);
            if (status != RequestRepository.Status.ACCEPTED) {
                assertTrue(button.isEnabled());
                button.performClick();
                assertNotEquals(repository.calls.get(0).draft.clientRequestId,
                        repository.calls.get(1).draft.clientRequestId);
            }
        });
    }

    @Test public void networkRetryOnSameBindingKeepsUuid() {
        scenario.onActivity(activity -> {
            TextView button = button(activity);
            bind(button, a); button.performClick();
            repository.fail(0);
            assertTrue(button.isEnabled());
            button.performClick();
            assertEquals(repository.calls.get(0).draft.clientRequestId,
                    repository.calls.get(1).draft.clientRequestId);
        });
    }

    private void bind(TextView button, MatchCandidate candidate) {
        MatchSummaryBinder.bindRequestButton(button, candidate,
                RequestDraft.verified(candidate.uid, candidate.uid, UUID.randomUUID().toString()), repository);
    }

    private static TextView button(ProfileDetailActivity activity) {
        TextView button = activity.findViewById(R.id.btnRequest);
        assertTrue(button.isAttachedToWindow());
        return button;
    }

    private static void noFeedback(ProfileDetailActivity activity) {
        activity.getSupportFragmentManager().executePendingTransactions();
        assertNull(activity.getSupportFragmentManager().findFragmentByTag("feedback"));
    }

    private static final class Call {
        final RequestDraft draft;
        final RequestRepository.Callback callback;
        Call(RequestDraft draft, RequestRepository.Callback callback) { this.draft = draft; this.callback = callback; }
    }

    /** Firebase 입력 계약을 쓰되 네트워크 없이 응답 순서만 제어한다. */
    private static final class DelayedRepository implements RequestRepository {
        final List<Call> calls = new ArrayList<>();
        final Map<String, Item> items = new HashMap<>();
        final Set<String> submitting = new HashSet<>();
        @Override public void create(RequestDraft draft, Callback callback) {
            submitting.add(draft.toUid);
            calls.add(new Call(draft, callback));
        }
        void succeed(int index, Status status) {
            Call call = calls.get(index);
            submitting.remove(call.draft.toUid);
            Item item = new Item("request-" + index, "viewer", call.draft.toUid, "viewer", "스터디",
                    "2026-11-13", 600, 690, status, null, index);
            items.put(item.id, item);
            call.callback.onSuccess(item);
        }
        void fail(int index) {
            Call call = calls.get(index);
            submitting.remove(call.draft.toUid);
            call.callback.onError("네트워크 오류");
        }
        @Override public boolean hasPendingRequest(String uid) {
            for (Item item : items.values()) if (uid.equals(item.toUid) && item.status() == Status.PENDING) return true;
            return false;
        }
        @Override public boolean isSubmitting(String uid) { return submitting.contains(uid); }
        @Override public boolean isMock() { return false; }
        @Override public Item find(String id) { return items.get(id); }
        @Override public List<Item> list(Tab tab) { return Collections.emptyList(); }
        @Override public void cancel(String id, Callback callback) { throw new UnsupportedOperationException(); }
        @Override public boolean respond(String id, Status status) { return false; }
        @Override public Registration observe(Runnable listener) { return () -> {}; }
        @Override public boolean isLoading() { return false; }
        @Override public String error() { return null; }
        @Override public void retry() {}
        @Override public boolean isUpdating(String id) { return false; }
    }
}
