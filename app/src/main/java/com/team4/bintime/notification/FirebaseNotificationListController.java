package com.team4.bintime.notification;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import androidx.fragment.app.Fragment;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.common.StateView;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class FirebaseNotificationListController {
    private final Fragment fragment;
    private ListenerRegistration registration;
    private int generation;
    FirebaseNotificationListController(Fragment fragment) { this.fragment = fragment; }
    void start() {
        stop(); View root = fragment.getView(); if (root == null) return;
        int visit = generation;
        String uid = AuthRepositories.get(root.getContext()).getCurrentUid();
        ((TextView) root.findViewById(R.id.notificationFooter)).setText("최근 알림 50개를 표시해요");
        ((TextView) root.findViewById(R.id.notificationDate)).setText(java.time.LocalDate.now(ZoneId.of("Asia/Seoul"))
                .format(DateTimeFormatter.ofPattern("오늘 · M월 d일 (E)", java.util.Locale.KOREAN)));
        StateView state = root.findViewById(R.id.notificationEmpty); LinearLayout rows = root.findViewById(R.id.notificationRows);
        state.showLoading(); rows.removeAllViews();
        if (uid == null) { state.showError("로그인이 필요합니다"); return; }
        registration = FirebaseFirestore.getInstance().collection("notifications").document(uid).collection("items")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(50).addSnapshotListener((snapshot, error) -> {
                    if (visit != generation || fragment.getView() != root || !uid.equals(AuthRepositories.get(root.getContext()).getCurrentUid())) return;
                    rows.removeAllViews();
                    if (error != null) { state.showError("알림을 불러오지 못했어요").primary("다시 시도", v -> start()); return; }
                    if (snapshot == null) return;
                    if (snapshot.isEmpty()) { state.showEmpty(root.getContext().getString(R.string.notification_empty), root.getContext().getString(R.string.notification_empty_message)); return; }
                    state.hide();
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snapshot.getDocuments()) {
                        View row = fragment.getLayoutInflater().inflate(R.layout.item_notification, rows, false);
                        ((TextView) row.findViewById(R.id.notificationTitle)).setText(doc.getString("title"));
                        String type = doc.getString("type");
                        ((TextView) row.findViewById(R.id.notificationBody)).setText("reviewReceived".equals(type) ? "내 후기를 작성한 뒤 상대 후기를 확인할 수 있어요" : "자세한 내용을 확인해보세요");
                        Timestamp created = doc.getTimestamp("createdAt");
                        ((TextView) row.findViewById(R.id.notificationTime)).setText(created == null ? "" : DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(created.toDate().toInstant()));
                        ((TextView) row.findViewById(R.id.notificationAction)).setText("확인하기 ›");
                        ((ImageView) row.findViewById(R.id.notificationIcon)).setImageResource("message".equals(type) ? R.drawable.ic_nav_chat : R.drawable.ic_users_round);
                        row.setAlpha(doc.get("readAt") == null ? 1f : .65f);
                        row.setOnClickListener(v -> NotificationNavigator.open((MainActivity) fragment.requireActivity(), uid, doc.getId()));
                        rows.addView(row);
                    }
                });
    }
    void stop() { generation++; if (registration != null) registration.remove(); registration = null; }
}
