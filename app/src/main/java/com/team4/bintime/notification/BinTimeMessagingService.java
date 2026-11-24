package com.team4.bintime.notification;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import java.util.Map;

/** Data-only pushes are displayed only after validating the currently signed-in UID. */
public final class BinTimeMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(@NonNull String token) { NotificationDevices.register(this); }
    @Override public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String uid = AuthRepositories.get(this).getCurrentUid(), id = data.get("notificationId");
        if (uid == null || !uid.equals(data.get("recipientUid")) || id == null || !id.matches("[a-f0-9]{64}")) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("bintime_events", "매칭 및 대화 알림", NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("notificationId", id).putExtra("notificationUid", uid);
        PendingIntent pending = PendingIntent.getActivity(this, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(id, 0, new NotificationCompat.Builder(this, "bintime_events").setSmallIcon(R.drawable.ic_nav_chat)
                .setContentTitle(data.getOrDefault("title", "새 알림이 도착했어요"))
                .setContentText("앱에서 확인해주세요").setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(pending).build());
    }
}
