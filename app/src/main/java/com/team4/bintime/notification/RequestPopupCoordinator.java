package com.team4.bintime.notification;

import androidx.fragment.app.Fragment;
import com.google.firebase.firestore.ListenerRegistration;
import com.team4.bintime.MainActivity;
import com.team4.bintime.request.RequestRepository;

/** A popup is a view of the shared request repository, not a separate pending-state copy. */
public final class RequestPopupCoordinator {
    private final Fragment fragment;
    private final RequestRepository requests;
    private NotificationSettingsRepository settings;
    private NotificationSettingsRepository.Snapshot snapshot;
    private ListenerRegistration preferences;
    private RequestRepository.Registration requestListener;
    private boolean checking;
    private int generation;
    public RequestPopupCoordinator(Fragment fragment, RequestRepository requests) { this.fragment = fragment; this.requests = requests; }
    public void start() {
        if (preferences != null || !fragment.isAdded()) return;
        settings = new NotificationSettingsRepository(fragment.requireContext());
        int visit = ++generation;
        preferences = settings.observe(new NotificationSettingsRepository.Listener() {
            @Override public void onData(NotificationSettingsRepository.Snapshot loaded) { if (visit == generation) { snapshot = loaded; maybeShow(); } }
            @Override public void onError() { snapshot = null; }
        });
        requestListener = requests.observe(this::maybeShow);
    }
    private void maybeShow() {
        if (checking || snapshot == null || !snapshot.enabled("popup") || !settings.current() || !fragment.isResumed()
                || fragment.isHidden() || fragment.getView() == null || fragment.getChildFragmentManager().isStateSaved()
                || fragment.getChildFragmentManager().findFragmentByTag(MatchRequestDialog.RESULT) != null) return;
        java.util.List<RequestRepository.Item> pending = requests.list(RequestRepository.Tab.RECEIVED);
        if (pending.isEmpty()) return;
        String id = pending.get(0).id; int visit = generation; checking = true;
        settings.popupAllowed(allowed -> {
            if (visit != generation) return;
            checking = false;
            RequestRepository.Item item = requests.find(id);
            if (!allowed || snapshot == null || !snapshot.enabled("popup") || !fragment.isAdded() || !fragment.isResumed()
                    || fragment.isHidden() || fragment.getChildFragmentManager().isStateSaved() || item == null
                    || item.status() != RequestRepository.Status.PENDING || item.sent
                    || !item.toUid.equals(com.team4.bintime.auth.AuthRepositories.currentUid())) return;
            if (((MainActivity) fragment.requireActivity()).claimRequestPopup()) MatchRequestDialog.showRequest(fragment.getChildFragmentManager(), id);
        });
    }
    public void close() {
        generation++; checking = false; snapshot = null;
        if (preferences != null) preferences.remove(); preferences = null;
        if (requestListener != null) requestListener.remove(); requestListener = null;
    }
}
