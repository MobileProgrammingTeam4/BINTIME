package com.team4.bintime.notification;

import android.content.Context;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.team4.bintime.auth.AuthRepositories;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;

/** Tokens are never logged or exposed through public profile fields. */
public final class NotificationDevices {
    private static volatile String unregisteringUid;
    // Task listeners and sign-out run on the main thread. Drain dispatched registration calls first.
    private static final List<Task<?>> registrations = new ArrayList<>();
    private NotificationDevices() {}
    public static void register(Context context) {
        if (!AuthRepositories.isFirebaseEnabled(context)) return;
        if (com.google.firebase.FirebaseApp.getInstance().getOptions().getProjectId().startsWith("demo-")) return;
        String uid = AuthRepositories.get(context).getCurrentUid();
        if (uid == null || uid.equals(unregisteringUid)) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (uid.equals(AuthRepositories.get(context).getCurrentUid()) && !uid.equals(unregisteringUid)) {
                Task<?> registration = FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("registerNotificationDevice")
                        .call(Collections.singletonMap("token", token));
                registrations.add(registration);
                registration.addOnCompleteListener(task -> registrations.remove(registration));
            }
        });
    }

    /** Best effort while still authenticated. Offline failure must not prevent sign-out. */
    public static void unregister(Context context, Runnable complete) {
        if (!AuthRepositories.isFirebaseEnabled(context)
                || com.google.firebase.FirebaseApp.getInstance().getOptions().getProjectId().startsWith("demo-")) {
            complete.run(); return;
        }
        String uid = AuthRepositories.get(context).getCurrentUid();
        if (uid == null) { complete.run(); return; }
        unregisteringUid = uid;
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean completed = new AtomicBoolean();
        Runnable finish = () -> {
            if (!completed.compareAndSet(false, true)) return;
            try { complete.run(); }
            finally { if (uid.equals(unregisteringUid)) unregisteringUid = null; }
        };
        main.postDelayed(finish, 3000);
        Runnable remove = () -> {
            if (completed.get()) return;
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
                if (completed.get() || !uid.equals(AuthRepositories.get(context).getCurrentUid())) { finish.run(); return; }
                Map<String, Object> input = new HashMap<>(); input.put("token", token); input.put("remove", true);
                FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("registerNotificationDevice").call(input)
                        .addOnCompleteListener(task -> { main.removeCallbacks(finish); finish.run(); });
            }).addOnFailureListener(error -> { main.removeCallbacks(finish); finish.run(); });
        };
        Tasks.whenAllComplete(new ArrayList<>(registrations)).addOnCompleteListener(task -> remove.run());
    }
    public static void clearVisible(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancelAll();
    }
}
