package com.team4.bintime.chat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
import com.team4.bintime.common.ActionMenu;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.ConfirmDialog;
import com.team4.bintime.common.StateView;
import com.team4.bintime.report.ReportActivity;
import com.team4.bintime.review.MockReviewRepository;
import com.team4.bintime.review.ReviewDetailActivity;
import com.team4.bintime.review.ReviewRepository;
import com.team4.bintime.review.ReviewWriteActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 1:1 채팅. 종료된 매칭도 입력과 송수신을 그대로 유지한다.
 * ⋯ 옵션(매칭 종료·나가기·신고), AI 답장 칩, 후기 이동은 BT-010, 남은 시간 1초 갱신은 BT-035 범위다.
 */
public class ChatRoomActivity extends AppCompatActivity {

    private static final String EXTRA_MATCH_ID = "matchId";
    private static final String KEY_END_MATCH = "chat.endMatch";
    private static final String KEY_LEAVE_ROOM = "chat.leaveRoom";
    private static final String TAG_LEAVE_BLOCKED = "chat.leaveBlocked";
    private static final String STATE_REPLIES = "replies";

    public static Intent intent(Context context, String matchId) {
        return new Intent(context, ChatRoomActivity.class).putExtra(EXTRA_MATCH_ID, matchId);
    }

    private final ChatRepository repository = MockChatRepository.shared();
    private final ReplySuggestionRepository replyRepository = MockReplySuggestionRepository.shared();
    private final ReviewRepository reviewRepository = MockReviewRepository.shared();

    private String matchId;
    private ChatRoom room;
    private ChatRepository.Registration roomRegistration, messageRegistration;
    private ReplySuggestionRepository.Request replyRequest;
    private ChatMessageAdapter adapter;
    private RecyclerView messageList;
    private StateView stateView;
    private EditText input;
    private View aiButton;
    private LinearLayout replyChips;
    private ArrayList<String> replies;
    /** null 이면 아직 확인 전 */
    private Boolean myReviewWritten;
    private boolean actionInProgress, leaving;

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
        findViewById(R.id.chatMore).setOnClickListener(this::showOptions);
        findViewById(R.id.chatEndedBanner).setOnClickListener(v -> openReview());
        stateView = findViewById(R.id.chatRoomState);
        input = findViewById(R.id.chatInput);
        findViewById(R.id.chatSend).setOnClickListener(v -> send());
        aiButton = findViewById(R.id.chatAiReply);
        aiButton.setOnClickListener(v -> requestReplies());
        replyChips = findViewById(R.id.chatReplyChips);

        // 확인 다이얼로그 결과는 화면 회전 뒤에도 받도록 매번 등록한다.
        getSupportFragmentManager().setFragmentResultListener(KEY_END_MATCH, this, (key, result) -> endMatch());
        getSupportFragmentManager().setFragmentResultListener(KEY_LEAVE_ROOM, this, (key, result) -> leaveRoom());

        adapter = new ChatMessageAdapter(repository.currentUid());
        messageList = findViewById(R.id.chatMessageList);
        messageList.setLayoutManager(new LinearLayoutManager(this));
        messageList.setAdapter(adapter);
        // 키보드가 올라와 목록이 줄어들면 마지막 메시지가 보이도록 맞춘다.
        messageList.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom) messageList.post(this::scrollToLast);
        });

        if (savedInstanceState != null) {
            ArrayList<String> saved = savedInstanceState.getStringArrayList(STATE_REPLIES);
            if (saved != null) showReplies(saved);
        }

        roomRegistration = repository.observeRoom(matchId, new ChatRepository.Listener<ChatRoom>() {
            @Override
            public void onData(ChatRoom data) {
                room = data;
                adapter.setRoom(data);
                bindRoom();
            }

            @Override
            public void onError() {
                // 직접 나간 방은 구독이 끊기는 것이 정상이다.
                if (!leaving) closeMissingRoom();
            }
        });
        subscribeMessages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (matchId == null) return;
        repository.markRead(matchId);
        // 후기 화면에서 돌아오면 배너 문구(남기기 → 보기)를 다시 맞춘다.
        refreshReviewStatus();
        if (room != null) bindRoom();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (replies != null) outState.putStringArrayList(STATE_REPLIES, replies);
    }

    @Override
    protected void onDestroy() {
        if (roomRegistration != null) roomRegistration.remove();
        if (messageRegistration != null) messageRegistration.remove();
        if (replyRequest != null) replyRequest.cancel();
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
        if (active) bindMatchInfo();
        else bindReviewLink();
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

    // ---- 후기 ----

    private void refreshReviewStatus() {
        reviewRepository.loadMyReviewStatus(matchId, new ReviewRepository.StatusCallback() {
            @Override
            public void onResult(boolean written) {
                myReviewWritten = written;
                if (room != null && room.status == ChatRoom.Status.ENDED) bindReviewLink();
            }

            @Override
            public void onError() {
                myReviewWritten = null;
                if (room != null && room.status == ChatRoom.Status.ENDED) bindReviewLink();
            }
        });
    }

    private void bindReviewLink() {
        TextView link = findViewById(R.id.chatReviewLink);
        link.setVisibility(myReviewWritten == null ? View.INVISIBLE : View.VISIBLE);
        if (myReviewWritten != null) {
            link.setText(myReviewWritten ? R.string.chat_review_view : R.string.chat_review_write);
        }
    }

    private void openReview() {
        if (room == null || room.status != ChatRoom.Status.ENDED || myReviewWritten == null) return;
        PartnerSummary partner = PartnerSummary.from(this, room, System.currentTimeMillis());
        startActivity(myReviewWritten
                ? ReviewDetailActivity.intent(this, matchId, partner)
                : ReviewWriteActivity.intent(this, matchId, partner));
    }

    // ---- 채팅 옵션 (Figma 120:2575) ----

    private void showOptions(View anchor) {
        if (room == null || actionInProgress) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        // 이미 종료된 매칭은 다시 종료할 수 없어 메뉴에서 뺀다.
        if (room.status == ChatRoom.Status.ACTIVE) {
            labels.add(getString(R.string.chat_option_end));
            actions.add(this::confirmEndMatch);
        }
        labels.add(getString(R.string.chat_option_leave));
        actions.add(this::confirmLeaveRoom);
        labels.add(getString(R.string.chat_option_report));
        actions.add(this::openReport);
        // 마지막 항목(신고하기)을 빨간 글자로 표시한다.
        String[] items = labels.toArray(new String[0]);
        PopupWindow popup = ActionMenu.show(anchor, items, items.length - 1, index -> actions.get(index).run());
        fitMenuWidth(popup, anchor, items);
    }

    /**
     * 공통 ActionMenu 는 폭이 116dp 로 고정이라 "매칭 종료하기"가 두 줄로 접힌다.
     * common/ 을 고치지 않고 이 화면에서만 가장 긴 문구에 맞춰 넓힌다(A 에게 폭 옵션 제안 예정).
     */
    private void fitMenuWidth(PopupWindow popup, View anchor, String[] items) {
        Paint paint = new Paint();
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        float widest = 0;
        for (String item : items) widest = Math.max(widest, paint.measureText(item));
        // 팝업 좌우 여백 12dp×2 + 항목 좌우 여백 8dp×2 + 여유 8dp
        int width = (int) Math.ceil(widest) + ChipViews.dp(this, 48);
        if (width > popup.getWidth()) {
            popup.update(anchor, 0, ChipViews.dp(this, 4), width, popup.getHeight());
        }
    }

    private void confirmEndMatch() {
        ConfirmDialog.show(getSupportFragmentManager(), KEY_END_MATCH, getString(R.string.chat_end_title),
                getString(R.string.chat_end_message), getString(R.string.chat_end_confirm));
    }

    private void confirmLeaveRoom() {
        if (room.status == ChatRoom.Status.ACTIVE) {
            // 진행 중 매칭은 나갈 수 없다. 매칭 종료를 먼저 해야 한다.
            ChatNoticeDialog.show(getSupportFragmentManager(), TAG_LEAVE_BLOCKED,
                    getString(R.string.chat_leave_blocked_title), getString(R.string.chat_leave_blocked_message),
                    getString(R.string.chat_close));
            return;
        }
        ConfirmDialog.show(getSupportFragmentManager(), KEY_LEAVE_ROOM, getString(R.string.chat_leave_title),
                getString(R.string.chat_leave_message), getString(R.string.chat_leave_confirm));
    }

    private void endMatch() {
        if (actionInProgress) return;
        actionInProgress = true;
        repository.endMatch(matchId, new ChatRepository.ActionCallback() {
            @Override
            public void onSuccess() {
                // 화면 전환은 observeRoom 이 ENDED 를 전달하며 처리한다.
                actionInProgress = false;
            }

            @Override
            public void onError() {
                actionInProgress = false;
                Toast.makeText(ChatRoomActivity.this, R.string.chat_end_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void leaveRoom() {
        if (actionInProgress || room == null || room.status != ChatRoom.Status.ENDED) return;
        actionInProgress = true;
        leaving = true;
        repository.leaveRoom(matchId, new ChatRepository.ActionCallback() {
            @Override
            public void onSuccess() {
                Toast.makeText(ChatRoomActivity.this, R.string.chat_leave_done, Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onError() {
                actionInProgress = false;
                leaving = false;
                Toast.makeText(ChatRoomActivity.this, R.string.chat_leave_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openReport() {
        startActivity(ReportActivity.intent(this, PartnerSummary.from(this, room, System.currentTimeMillis()),
                matchId));
    }

    // ---- AI 답장 칩 ----

    /** 버튼을 눌렀을 때만 요청한다. 요청 중에는 버튼을 잠가 중복 호출을 막는다. */
    private void requestReplies() {
        if (replyRequest != null) return;
        setAiButtonEnabled(false);
        replyRequest = replyRepository.requestReplies(matchId, new ReplySuggestionRepository.Callback() {
            @Override
            public void onSuccess(List<String> result) {
                replyRequest = null;
                setAiButtonEnabled(true);
                showReplies(new ArrayList<>(result));
            }

            @Override
            public void onError() {
                replyRequest = null;
                setAiButtonEnabled(true);
                hideReplies();
                Toast.makeText(ChatRoomActivity.this, R.string.chat_ai_reply_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setAiButtonEnabled(boolean enabled) {
        aiButton.setEnabled(enabled);
        aiButton.setAlpha(enabled ? 1f : 0.4f);
    }

    private void showReplies(ArrayList<String> result) {
        replies = result;
        replyChips.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String reply : result) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_chat_reply_chip, replyChips, false);
            chip.setText(reply);
            chip.setOnClickListener(v -> fillInput(reply));
            replyChips.addView(chip);
        }
        replyChips.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void hideReplies() {
        replies = null;
        replyChips.removeAllViews();
        replyChips.setVisibility(View.GONE);
    }

    /** 칩 문구를 입력창에만 넣는다. 전송은 사용자가 직접 한다(자동 전송 금지). */
    private void fillInput(String reply) {
        input.setText(reply);
        input.setSelection(input.length());
        input.requestFocus();
    }

    // ---- 전송 ----

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
