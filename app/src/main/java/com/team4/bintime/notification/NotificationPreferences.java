package com.team4.bintime.notification;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.time.ZoneId;

/** 기기 내 UI 목업 설정. FCM 구독/OS 알림 권한을 변경하지 않는다. */
public final class NotificationPreferences {
    private final SharedPreferences prefs;
    public static final String[] KEYS = {"message", "request", "accepted", "availability", "reviewWrite", "reviewReceived", "popup"};
    public NotificationPreferences(Context context) {
        String uid = com.team4.bintime.auth.AuthRepositories.get(context).getCurrentUid();
        prefs = context.getSharedPreferences("mockNotificationSettings_" + (uid == null ? "anonymous" : uid), Context.MODE_PRIVATE);
    }
    public boolean enabled(String key) { return prefs.getBoolean(key, key.equals("message") || key.equals("request") || key.equals("popup")); }
    public void set(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    public void suppressToday(boolean value) { prefs.edit().putString("suppressedDate", value ? today() : "").apply(); }
    public boolean suppressedToday() { return today().equals(prefs.getString("suppressedDate", "")); }
    public boolean canShowPopup() { return PopupPolicy.canShow(enabled("popup"), prefs.getString("suppressedDate", ""), today()); }
    private static String today() { return LocalDate.now(ZoneId.of("Asia/Seoul")).toString(); }
}
