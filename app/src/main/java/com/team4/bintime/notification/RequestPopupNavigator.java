package com.team4.bintime.notification;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.request.RequestRepository;

/** Explicit inbox navigation may open a request even when automatic popups are suppressed. */
public final class RequestPopupNavigator {
    private RequestPopupNavigator() {}
    public static void open(MainActivity activity, String id) {
        activity.openRequestTab(RequestRepository.Tab.RECEIVED);
        String uid = AuthRepositories.get(activity).getCurrentUid();
        RequestRepository requests = activity.requests();
        boolean[] done = {false}; RequestRepository.Registration[] subscription = {null};
        LifecycleEventObserver[] lifecycle = {null};
        Runnable close = () -> {
            done[0] = true;
            if (subscription[0] != null) subscription[0].remove();
            if (lifecycle[0] != null) activity.getLifecycle().removeObserver(lifecycle[0]);
        };
        lifecycle[0] = (owner, event) -> { if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) close.run(); };
        activity.getLifecycle().addObserver(lifecycle[0]);
        subscription[0] = requests.observe(() -> {
            if (done[0]) return;
            if (activity.isFinishing() || activity.isDestroyed() || uid == null || !uid.equals(AuthRepositories.get(activity).getCurrentUid())) { close.run(); return; }
            RequestRepository.Item item = requests.find(id);
            if (item == null && requests.isLoading()) return;
            Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag("tab_" + R.id.nav_matching);
            close.run();
            if (fragment == null || fragment.isHidden() || fragment.getChildFragmentManager().isStateSaved()) return;
            if (item != null && uid.equals(item.toUid) && item.status() == RequestRepository.Status.PENDING)
                MatchRequestDialog.showRequest(fragment.getChildFragmentManager(), id);
            else Toast.makeText(activity, R.string.notification_processed, Toast.LENGTH_SHORT).show();
        });
        if (done[0]) subscription[0].remove();
        new Handler(Looper.getMainLooper()).postDelayed(close, 10_000);
    }
}
