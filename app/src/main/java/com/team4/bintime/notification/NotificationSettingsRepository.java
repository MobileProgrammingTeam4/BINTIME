package com.team4.bintime.notification;

import android.content.Context;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.functions.FirebaseFunctions;
import com.team4.bintime.auth.AuthRepositories;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

/** UID-scoped remote preferences. No optimistic cross-account cache or shared device settings. */
public final class NotificationSettingsRepository {
    public interface Listener { void onData(Snapshot snapshot); void onError(); }
    public interface Callback { void complete(boolean saved); }
    public static final class Snapshot {
        private final Map<String, Object> values;
        Snapshot(Map<String, Object> values) { this.values = values == null ? Collections.emptyMap() : values; }
        public boolean enabled(String key) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : key.equals("message") || key.equals("request") || key.equals("popup");
        }
        public String suppressedDate() { Object value = values.get("suppressedDate"); return value instanceof String ? (String) value : ""; }
    }
    private final Context context;
    private final String uid;
    public NotificationSettingsRepository(Context context) {
        this.context = context.getApplicationContext(); uid = AuthRepositories.get(context).getCurrentUid();
    }
    public boolean current() { return uid != null && uid.equals(AuthRepositories.get(context).getCurrentUid()); }
    public ListenerRegistration observe(Listener listener) {
        if (!current()) { listener.onError(); return () -> {}; }
        return FirebaseFirestore.getInstance().collection("notificationSettings").document(uid).addSnapshotListener((doc, error) -> {
            if (!current()) return;
            if (error != null) listener.onError(); else if (doc != null) listener.onData(new Snapshot(doc.getData()));
        });
    }
    public void set(String key, boolean value, Callback callback) {
        if (!current()) { callback.complete(false); return; }
        Map<String, Object> data = new HashMap<>(); data.put("key", key); data.put("value", value);
        FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("updateNotificationSettings").call(data)
                .addOnSuccessListener(result -> callback.complete(current()))
                .addOnFailureListener(error -> callback.complete(false));
    }
    public void popupAllowed(java.util.function.Consumer<Boolean> callback) {
        policy(data -> callback.accept(Boolean.TRUE.equals(data.get("allowed"))));
    }
    public void serverToday(java.util.function.Consumer<String> callback) {
        policy(data -> callback.accept(data.get("today") instanceof String ? (String) data.get("today") : ""));
    }
    private void policy(java.util.function.Consumer<Map<?, ?>> callback) {
        if (!current()) { callback.accept(Collections.emptyMap()); return; }
        FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("requestPopupPolicy").call(Collections.emptyMap())
                .addOnSuccessListener(result -> { Object data = result.getData(); callback.accept(current() && data instanceof Map ? (Map<?, ?>) data : Collections.emptyMap()); })
                .addOnFailureListener(error -> callback.accept(Collections.emptyMap()));
    }
}
