package com.team4.bintime.notification;

import android.content.Context;
import android.app.NotificationManager;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.functions.FirebaseFunctions;
import com.team4.bintime.auth.AuthRepositories;
import java.util.Collections;

/** Tokens are never logged or exposed through public profile fields. */
public final class NotificationDevices {
    private NotificationDevices() {}
    public static void register(Context context) {
        if (!AuthRepositories.isFirebaseEnabled(context)) return;
        if (com.google.firebase.FirebaseApp.getInstance().getOptions().getProjectId().startsWith("demo-")) return;
        String uid = AuthRepositories.get(context).getCurrentUid();
        if (uid == null) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (uid.equals(AuthRepositories.get(context).getCurrentUid()))
                FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("registerNotificationDevice")
                        .call(Collections.singletonMap("token", token));
        });
    }
    public static void clearVisible(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancelAll();
    }
}
