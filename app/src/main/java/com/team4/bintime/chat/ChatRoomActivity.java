package com.team4.bintime.chat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.team4.bintime.R;
import com.team4.bintime.common.StateView;

import java.util.List;

/**
 * 1:1 채팅. 종료된 매칭도 입력과 송수신을 그대로 유지한다.
 * ⋯ 옵션, AI 답장, 후기 이동은 BT-010, 남은 시간 1초 갱신은 BT-035 범위다.
 */
public class ChatRoomActivity extends AppCompatActivity {

    private static final String EXTRA_MATCH_ID = "matchId";

    public static Intent intent(Context context, String matchId) {
        return new Intent(context, ChatRoomActivity.class).putExtra(EXTRA_MATCH_ID, matchId);
    }

    private final ChatRepository repository = MockChatRepository.shared();

    private String matchId;
    private ChatRoom room;
    private ChatRepository.Registration roomRegistration, messageRegistration;
    private ChatMessageAdapter adapter;
    private RecyclerView messageList;
    private StateView stateView;
    private EditText input;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        matchId = getIntent().getStringExtra(EXTRA_MATCH_ID);
        if (matchId == null) {
            closeMissingRoom();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_chat_room);
        applyWindowInsets(findViewById(R.id.chatRoomRoot));

        findViewById(R.id.chatBack).setOnClickListener(v -> finish());
        stateView = findViewById(R.id.chatRoomState);
        input = findViewById(R.id.chatInput);
        findViewById(R.id.chatSend).setOnClickListener(v -> send());

        adapter = new ChatMessageAdapter(repository.currentUid());
        messageList = findViewById(R.id.chatMessageList);
        messageList.setLayoutManager(new LinearLayoutManager(this));
        messageList.setAdapter(adapter);
        // 키보드가 올라와 목록이 줄어들면 마지막 메시지가 보이도록 맞춘다.
        messageList.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom) messageList.post(this::scrollToLast);
        });

        roomRegistration = repository.observeRoom(matchId, new ChatRepository.Listener<ChatRoom>() {
            @Override
            public void onData(ChatRoom data) {
                room = data;
                adapter.setRoom(data);
                bindRoom();
            }

            @Override
            public void onError() {
                closeMissingRoom();
            }
        });
        subscribeMessages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (matchId == null) return;
        repository.markRead(matchId);
        if (room != null) bindRoom();
    }

    @Override
    protected void onDestroy() {
        if (roomRegistration != null) roomRegistration.remove();
        if (messageRegistration != null) messageRegistration.remove();
        super.onDestroy();
    }

    private void subscribeMessages() {
        if (messageRegistration != null) messageRegistration.remove();
        stateView.showLoading();
        messageRegistration = repository.observeMessages(matchId, new ChatRepository.Listener<List<ChatMessage>>() {
            @Override
            public void onData(List<ChatMessage> data) {
                adapter.submit(data);
                if (data.isEmpty()) {
                    stateView.showEmpty(getString(R.string.chat_messages_empty_title),
                            getString(R.string.chat_messages_empty_message));
                } else {
                    stateView.hide();
                    scrollToLast();
                }
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                    repository.markRead(matchId);
                }
            }

            @Override
            public void onError() {
                stateView.showError(getString(R.string.chat_messages_error))
                        .primary(getString(R.string.chat_retry), v -> subscribeMessages());
            }
        });
    }

    private void bindRoom() {
        ((TextView) findViewById(R.id.chatRoomTitle)).setText(room.otherName);
        boolean active = room.status == ChatRoom.Status.ACTIVE;
        findViewById(R.id.chatMatchInfo).setVisibility(active ? View.VISIBLE : View.GONE);
        findViewById(R.id.chatEndedBanner).setVisibility(active ? View.GONE : View.VISIBLE);
        findViewById(R.id.chatReplyChips).setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) bindMatchInfo();
        else ((TextView) findViewById(R.id.chatReviewLink)).setText(room.reviewWritten
                ? R.string.chat_review_view : R.string.chat_review_write);
    }

    private void bindMatchInfo() {
        long now = System.currentTimeMillis();
        ((TextView) findViewById(R.id.chatMatchTime)).setText(ChatTimeFormatter.timeRange(room, now,
                getString(R.string.chat_today), getString(R.string.chat_yesterday)));
        ((TextView) findViewById(R.id.chatMatchRemaining)).setText(getString(R.string.chat_remaining,
                ChatTimeFormatter.remaining(room, now)));
        boolean hasPlace = room.recommendedPlace != null;
        findViewById(R.id.chatPlaceRow).setVisibility(hasPlace ? View.VISIBLE : View.GONE);
        if (hasPlace) {
            ((TextView) findViewById(R.id.chatPlace)).setText(getString(R.string.chat_recommended_place,
                    room.recommendedPlace));
        }
    }

    private void send() {
        String text = input.getText().toString();
        if (text.trim().isEmpty()) return;
        repository.sendMessage(matchId, text, new ChatRepository.SendCallback() {
            @Override
            public void onSuccess() {
                input.setText("");
            }

            @Override
            public void onError() {
                // 실패해도 작성한 내용은 입력창에 남겨 다시 보낼 수 있게 한다.
                Toast.makeText(ChatRoomActivity.this, R.string.chat_send_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void scrollToLast() {
        int count = adapter.getItemCount();
        if (count > 0) messageList.scrollToPosition(count - 1);
    }

    private void closeMissingRoom() {
        Toast.makeText(this, R.string.chat_room_missing, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** targetSdk 36 의 전체 화면 표시에서 입력창이 시스템 바와 키보드에 가리지 않게 한다. */
    private static void applyWindowInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
