package com.team4.bintime.request;

import android.view.View;
import android.widget.Toast;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;
import com.team4.bintime.R;
import com.team4.bintime.chat.ChatRoomActivity;
import com.team4.bintime.chat.MockChatRepository;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** 성사 화면·완료 카드가 같은 서버 방 준비/재시도 경로를 사용한다. */
public final class MatchChatNavigator {
    private MatchChatNavigator() {}
    public static void open(AppCompatActivity owner, String matchId, View source) {
        if (!source.isEnabled()) return;
        RequestRepository requests = RequestRepositories.get(owner);
        if (requests.isMock()) {
            RequestRepository.Item item = requests.find(matchId);
            if (item == null) return;
            MockChatRepository.shared().connectMatchForDemo(item);
            owner.startActivity(ChatRoomActivity.intent(owner, matchId)); return;
        }
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;
        CharSequence label = source instanceof TextView ? ((TextView) source).getText() : null;
        if (label != null) ((TextView) source).setText(R.string.chat_link_preparing);
        source.setEnabled(false);
        FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("ensureMatchChat")
                .call(Collections.singletonMap("matchId", matchId)).addOnCompleteListener(task -> {
            if (owner.isFinishing() || owner.isDestroyed()) return;
            source.setEnabled(true);
            if (label != null) ((TextView) source).setText(label);
            if (!Objects.equals(uid, FirebaseAuth.getInstance().getUid()) || !owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
            Object raw = task.isSuccessful() ? task.getResult().getData() : null;
            if (raw instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) raw).get("chatReady")) && ((Map<?, ?>) raw).get("chatRoomId") instanceof String) {
                owner.startActivity(ChatRoomActivity.intent(owner, (String) ((Map<?, ?>) raw).get("chatRoomId")));
            } else Toast.makeText(owner, task.isSuccessful() ? R.string.chat_archive_read_only : R.string.chat_link_failed, Toast.LENGTH_LONG).show();
        });
    }
}
