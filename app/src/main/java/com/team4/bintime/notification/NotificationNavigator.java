package com.team4.bintime.notification;

import android.content.Intent;
import android.widget.Toast;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldValue;
import com.team4.bintime.MainActivity;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.chat.ChatRoomActivity;
import com.team4.bintime.request.RequestRepository;

/** Intent values only locate the owner's inbox document; routing data is re-read from Firestore. */
public final class NotificationNavigator {
    private NotificationNavigator() {}
    public static void open(MainActivity activity, String uid, String id) {
        if (uid == null || !uid.equals(AuthRepositories.get(activity).getCurrentUid()) || id == null || !id.matches("[a-f0-9]{64}")) return;
        FirebaseFirestore.getInstance().collection("notifications").document(uid).collection("items").document(id).get()
                .addOnSuccessListener(doc -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !uid.equals(AuthRepositories.get(activity).getCurrentUid())) return;
                    if (!doc.exists()) { Toast.makeText(activity, "알림을 찾을 수 없어요", Toast.LENGTH_SHORT).show(); return; }
                    if (doc.get("readAt") == null) doc.getReference().update("readAt", FieldValue.serverTimestamp());
                    String type = doc.getString("type"), target = doc.getString("targetId");
                    if ("request".equals(type)) { RequestPopupNavigator.open(activity, target); return; }
                    if ("availability".equals(type)) { activity.openRequestTab(null); return; }
                    if ("message".equals(type) && target != null) { activity.startActivity(ChatRoomActivity.intent(activity, target)); return; }
                    // Resolve actual room ID from a match. A shared room may differ from the selected match ID.
                    if (target == null) return;
                    FirebaseFirestore.getInstance().collection("matches").document(target).get().addOnSuccessListener(match -> {
                        if (activity.isFinishing() || activity.isDestroyed() || !uid.equals(AuthRepositories.get(activity).getCurrentUid())) return;
                        String room = match.getString("chatRoomId");
                        if (room != null) activity.startActivity(ChatRoomActivity.intent(activity, room)
                                .putExtra("notificationReviewMatchId", type != null && type.startsWith("review") ? target : ""));
                        else activity.openRequestTab(RequestRepository.Tab.COMPLETED);
                    }).addOnFailureListener(e -> { if (!activity.isFinishing() && !activity.isDestroyed() && uid.equals(AuthRepositories.get(activity).getCurrentUid()))
                        Toast.makeText(activity, "연결된 매칭을 확인할 수 없어요", Toast.LENGTH_SHORT).show(); });
                }).addOnFailureListener(e -> { if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, "알림을 불러오지 못했어요", Toast.LENGTH_SHORT).show(); });
    }
    public static void fromIntent(MainActivity activity, Intent intent) {
        String id = intent.getStringExtra("notificationId"), uid = intent.getStringExtra("notificationUid");
        if (id != null) { intent.removeExtra("notificationId"); open(activity, uid, id); }
    }
}
