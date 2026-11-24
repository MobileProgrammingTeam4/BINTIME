package com.team4.bintime.notification;

import android.view.View;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Toast;
import androidx.fragment.app.DialogFragment;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.request.RequestRepository;
import com.team4.bintime.request.RequestTabsController;
import com.team4.bintime.request.MatchAcceptedActivity;
import com.team4.bintime.profile.ProfileRepositories;
import com.team4.bintime.profile.ProfileRepository;
import com.team4.bintime.profile.UserProfile;
import com.team4.bintime.matching.MatchSummaryBinder;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;
import java.util.Collections;

/** Existing popup layout, actual public profile, actual respond API, server-scoped daily preference. */
final class FirebaseRequestPopupController {
    private final DialogFragment fragment;
    private final View root;
    private final RequestRepository requests;
    private final String id;
    private final NotificationSettingsRepository settings;
    private RequestRepository.Registration registration;
    private com.google.firebase.firestore.ListenerRegistration preferences;
    private boolean sending, binding, active, todaySaving, lastSuppressed;
    private int generation;
    private String serverDate = "", suppressedDate = "";
    FirebaseRequestPopupController(DialogFragment fragment, View root, String id) {
        this.fragment = fragment; this.root = root; this.id = id;
        requests = ((MainActivity) fragment.requireActivity()).requests(); settings = new NotificationSettingsRepository(fragment.requireContext());
        root.findViewById(R.id.popupClose).setOnClickListener(v -> fragment.dismiss());
        root.findViewById(R.id.popupAccept).setOnClickListener(v -> respond(RequestRepository.Status.ACCEPTED));
        root.findViewById(R.id.popupReject).setOnClickListener(v -> respond(RequestRepository.Status.REJECTED));
        root.findViewById(R.id.summary).findViewById(R.id.avatar).setVisibility(View.GONE);
        View summary = root.findViewById(R.id.summary);
        ((TextView) summary.findViewById(R.id.name)).setText("상대방");
        for (int field : new int[]{R.id.info, R.id.matchReason, R.id.reasons, R.id.time, R.id.duration}) ((TextView) summary.findViewById(field)).setText("");
        summary.findViewById(R.id.matchRate).setVisibility(View.GONE);
        summary.findViewById(R.id.matchReason).setVisibility(View.GONE);
        summary.findViewById(R.id.aiPlace).setVisibility(View.GONE);
        CheckBox today = root.findViewById(R.id.popupToday); today.setEnabled(false);
        today.setOnCheckedChangeListener((button, checked) -> {
            if (binding) return;
            todaySaving = true; today.setEnabled(false);
            settings.set("suppressToday", checked, saved -> {
                // Complete in-flight state even while the dialog is stopped, so resuming can recover.
                todaySaving = false;
                if (!visible()) return;
                if (saved) refreshServerDate();
                else { bindToday(); Toast.makeText(root.getContext(), "설정을 저장하지 못했어요", Toast.LENGTH_SHORT).show(); }
            });
        });
        render();
    }
    private void loadProfile() {
        int visit = generation;
        RequestRepository.Item item = requests.find(id);
        if (item != null) ProfileRepositories.get(root.getContext()).getPublicProfile(item.profileUid, new ProfileRepository.Callback<UserProfile>() {
            @Override public void onSuccess(UserProfile profile) {
                if (visit != generation || !visible() || profile == null) return;
                int grade = profile.getGrade() != null && profile.getGrade().matches("[1-4]학년") ? profile.getGrade().charAt(0) - '0' : 0;
                MatchCandidate candidate = new MatchCandidate(item.profileUid, profile.getNickname(), profile.getDepartment(), grade,
                        profile.getGender(), MatchCandidate.NO_SCORE, "", minute(item.startMinute), minute(item.endMinute), item.purpose,
                        profile.getInterestTags() == null ? Collections.emptyList() : profile.getInterestTags(), Collections.emptyList(), profile.getBio());
                MatchSummaryBinder.bind(root.findViewById(R.id.summary), candidate, false);
                root.findViewById(R.id.summary).findViewById(R.id.avatar).setVisibility(View.GONE);
                ((TextView) root.findViewById(R.id.summary).findViewById(R.id.info)).setText(profile.getFormattedSummary());
                if (profile.getPhotoPath() != null && !profile.getPhotoPath().isEmpty())
                    ProfileRepositories.get(root.getContext()).loadProfilePhoto(profile.getPhotoPath(), new ProfileRepository.Callback<android.graphics.Bitmap>() {
                        @Override public void onSuccess(android.graphics.Bitmap bitmap) {
                            if (visit == generation && visible() && bitmap != null) ((android.widget.ImageView) root.findViewById(R.id.popupProfilePhoto)).setImageBitmap(bitmap);
                        }
                        @Override public void onError(String message) { /* existing default profile remains */ }
                    });
                android.widget.LinearLayout tags = root.findViewById(R.id.popupTags); tags.removeAllViews();
                if (profile.getInterestTags() != null) for (String label : profile.getInterestTags())
                    tags.addView(com.team4.bintime.common.ChipViews.interest(root.getContext(), label, true));
                render(); ((TextView) root.findViewById(R.id.popupIntro)).setText(profile.getBio());
            }
            @Override public void onError(String message) { /* Time and action remain usable without optional profile. */ }
        });
    }
    void start() {
        int visit = ++generation;
        active = true; loadProfile();
        refreshServerDate();
        registration = requests.observe(() -> { if (visible()) render(); });
        preferences = settings.observe(new NotificationSettingsRepository.Listener() {
            @Override public void onData(NotificationSettingsRepository.Snapshot snapshot) {
                if (visit != generation || !visible()) return;
                suppressedDate = snapshot.suppressedDate(); bindToday();
            }
            @Override public void onError() { if (visit == generation && visible()) root.findViewById(R.id.popupToday).setEnabled(false); }
        });
    }
    private void refreshServerDate() {
        int visit = generation; serverDate = ""; bindToday();
        settings.serverToday(date -> { if (visit == generation && visible()) { serverDate = date; bindToday(); } });
    }
    private boolean visible() { return active && fragment.isAdded() && fragment.getDialog() != null && fragment.getDialog().isShowing() && settings.current(); }
    private void bindToday() {
        CheckBox today = root.findViewById(R.id.popupToday); binding = true;
        lastSuppressed = !serverDate.isEmpty() && serverDate.equals(suppressedDate);
        today.setChecked(lastSuppressed); today.setEnabled(!todaySaving && !serverDate.isEmpty()); binding = false;
    }
    private static String minute(int value) { return String.format(java.util.Locale.ROOT, "%02d:%02d", value / 60, value % 60); }
    private void render() {
        root.findViewById(R.id.summary).findViewById(R.id.timeOverlapDivider).setVisibility(View.GONE);
        root.findViewById(R.id.summary).findViewById(R.id.reasons).setVisibility(View.GONE);
        RequestRepository.Item item = requests.find(id);
        boolean pending = item != null && !item.sent && item.toUid.equals(com.team4.bintime.auth.AuthRepositories.currentUid())
                && item.status() == RequestRepository.Status.PENDING;
        root.findViewById(R.id.popupAccept).setEnabled(pending && !sending);
        root.findViewById(R.id.popupReject).setEnabled(pending && !sending);
        if (item != null) {
            ((TextView) root.findViewById(R.id.summary).findViewById(R.id.time)).setText(RequestTabsController.timeLabel(item));
            ((TextView) root.findViewById(R.id.summary).findViewById(R.id.duration)).setText(MatchFilter.formatDuration(item.endMinute-item.startMinute) + " 겹침");
        }
        ((TextView) root.findViewById(R.id.popupQuestion)).setText(pending ? R.string.notification_question : R.string.notification_processed);
    }
    private void respond(RequestRepository.Status status) {
        if (sending || !settings.current()) return;
        sending = true; render();
        requests.respond(id, status, new RequestRepository.Callback() {
            @Override public void onSuccess(RequestRepository.Item item) {
                sending = false;
                if (!visible()) return;
                if (status == RequestRepository.Status.ACCEPTED) fragment.startActivity(MatchAcceptedActivity.intent(root.getContext(), item));
                fragment.dismissAllowingStateLoss();
            }
            @Override public void onError(String message) {
                sending = false;
                if (!visible()) return;
                render(); Toast.makeText(root.getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
    void close() { active = false; generation++; if (registration != null) registration.remove(); if (preferences != null) preferences.remove(); registration = null; preferences = null; }
}
