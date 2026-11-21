package com.team4.bintime.request;

import android.view.View;
import android.widget.Toast;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import com.google.firebase.auth.FirebaseAuth;
import com.team4.bintime.R;
import com.team4.bintime.chat.ChatLinkLauncher;
import com.team4.bintime.chat.ChatRoomActivity;
import com.team4.bintime.chat.MockChatRepository;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 성사 화면·완료 카드가 같은 서버 방 준비/재시도 경로를 사용한다.
 * 재시도 규칙(CHAT_PAIR_BUSY의 blockingMatchId 선준비, retryAfterMillis 대기, 중복 호출 방지)은
 * chat/ChatLinkLauncher(BT-034, E)가 맡고, 여기서는 버튼 상태와 화면 이동만 다룬다.
 */
public final class MatchChatNavigator {
    private MatchChatNavigator() {}

    /** 화면 자체가 진입점인 경우(성사 화면). 화면 종료·백그라운드만 검사한다. */
    public static void open(AppCompatActivity owner, String matchId, View source) {
        open(owner, matchId, source, () -> true);
    }

    /**
     * stillShowing 은 응답이 늦게 왔을 때 버튼을 누른 화면(탭)이 아직 보이는지 답한다.
     * false 면 채팅방으로 이동하거나 실패 안내를 띄우지 않고 버튼만 되돌린다.
     */
    public static void open(AppCompatActivity owner, String matchId, View source, BooleanSupplier stillShowing) {
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
        ChatLinkLauncher.get(owner).start(uid, matchId, new ChatLinkLauncher.Listener() {
            private boolean preparing;

            @Override public void onPreparing() {
                preparing = true;
                if (label != null) ((TextView) source).setText(R.string.chat_link_preparing);
                source.setEnabled(false);
            }

            @Override public void onReady(String chatRoomId) {
                if (!restore()) return;
                owner.startActivity(ChatRoomActivity.intent(owner, chatRoomId));
            }

            @Override public void onFailed(ChatLinkLauncher.Failure failure) {
                if (!restore()) return;
                Toast.makeText(owner, message(failure), Toast.LENGTH_LONG).show();
            }

            /** 버튼을 되돌리고, 이 화면에 결과를 보여도 되면 true. 화면 종료·계정 변경·백그라운드·다른 탭이면 false. */
            private boolean restore() {
                if (owner.isFinishing() || owner.isDestroyed()) return false;
                if (preparing) {
                    source.setEnabled(true);
                    if (label != null) ((TextView) source).setText(label);
                }
                return Objects.equals(uid, FirebaseAuth.getInstance().getUid())
                        && owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                        && stillShowing.getAsBoolean();
            }
        });
    }

    private static int message(ChatLinkLauncher.Failure failure) {
        switch (failure) {
            case BUSY: return R.string.chat_link_wait;
            case ARCHIVED: return R.string.chat_archive_read_only;
            case PERMANENT: return R.string.chat_link_permanent_message;
            case LOGIN_REQUIRED: return R.string.chat_login_required;
            default: return R.string.chat_link_failed;
        }
    }
}
