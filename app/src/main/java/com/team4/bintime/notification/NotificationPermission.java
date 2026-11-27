package com.team4.bintime.notification;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import com.team4.bintime.auth.AuthRepositories;

/** OS permission belongs to this installation; remote notification preferences remain per UID. */
public final class NotificationPermission {
    private NotificationPermission() {}

    public static void requestOnce(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || activity.isFinishing() || activity.isDestroyed()
                || !AuthRepositories.isFirebaseEnabled(activity) || !AuthRepositories.get(activity).isSignedIn()
                || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences permission = activity.getSharedPreferences("notificationPermission", Activity.MODE_PRIVATE);
        if (permission.getBoolean("asked", false)) return;
        permission.edit().putBoolean("asked", true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 390);
    }
}
