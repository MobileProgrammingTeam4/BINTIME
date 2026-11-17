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
import com.team4.bintime.request.MatchAppointment;
import com.team4.bintime.request.MatchAppointmentPolicy;
import com.team4.bintime.request.MatchSelectionSheet;
import com.team4.bintime.auth.AuthRepositories;

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
    private static final String KEY_SELECT_END = "chat.selectEnd", KEY_SELECT_REVIEW = "chat.selectReview";
    private final java.util.Map<String, Boolean> reviewStatuses = new java.util.HashMap<>();
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (room != null) bindRoom(); ticker.postDelayed(this, 1000); }
    };

    public static Intent intent(Context context, String matchId) {
        return new Intent(context, ChatRoomActivity.class).putExtra(EXTRA_MATCH_ID, matchId);
    }

    /** Firebase 가 설정되면 RTDB, 아니면 Mock (ChatRepositories). AI 답장·후기는 아직 Mock 이다(BT-041·BT-044). */
    private ChatRepository repository;
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
    private boolean actionInProgress, leaving, sending;
    private boolean messagesLoaded, messagesEmpty;
    private View sendButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        matchId = getIntent().getStringExtra(EXTRA_MATCH_ID);
        if (matchId == null) {
            closeMissingRoom();
            return;
        }
        repository = ChatRepositories.get(this);
        String myUid = repository.currentUid();
        if (myUid == null || myUid.trim().isEmpty()) {
            // 로그인 전이면 내 메시지를 구분할 수 없다. 구독을 시작하지 않고 닫는다.
            matchId = null;
            Toast.makeText(this, R.string.chat_login_required, Toast.LENGTH_SHORT).show();
            finish();
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
        sendButton = findViewById(R.id.chatSend);
        sendButton.setOnClickListener(v -> send());
        aiButton = findViewById(R.id.chatAiReply);
        aiButton.setOnClickListener(v -> requestReplies());
        replyChips = findViewById(R.id.chatReplyChips);

        // 확인 다이얼로그 결과는 화면 회전 뒤에도 받도록 매번 등록한다.
        getSupportFragmentManager().setFragmentResultListener(KEY_END_MATCH, this, (key, result) -> endMatch(result.getString(MatchSelectionSheet.MATCH_ID)));
        getSupportFragmentManager().setFragmentResultListener(KEY_SELECT_END, this, (key, result) -> confirmSelectedEnd(result.getString(MatchSelectionSheet.MATCH_ID)));
        getSupportFragmentManager().setFragmentResultListener(KEY_SELECT_REVIEW, this, (key, result) -> openSelectedReview(result.getString(MatchSelectionSheet.MATCH_ID)));
        getSupportFragmentManager().setFragmentResultListener(KEY_LEAVE_ROOM, this, (key, result) -> leaveRoom());

        adapter = new ChatMessageAdapter(myUid);
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
                if (data.archived) messageList.post(ChatRoomActivity.this::scrollToLast);
                refreshReviewStatus();
                // 보고 있는 중에 서버 트리거가 내 안읽음 수를 올리면 바로 0 으로 되돌린다.
                if (data.unreadCount > 0 && isResumedNow()) repository.markRead(matchId);
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
        ticker.removeCallbacks(tick); ticker.postDelayed(tick, 1000);
    }
    @Override protected void onPause() { ticker.removeCallbacks(tick); super.onPause(); }

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
        ticker.removeCallbacks(tick);
        super.onDestroy();
    }

    private void subscribeMessages() {
        if (messageRegistration != null) messageRegistration.remove();
        stateView.showLoading();
        messageRegistration = repository.observeMessages(matchId, new ChatRepository.Listener<List<ChatMessage>>() {
            @Override
            public void onData(List<ChatMessage> data) {
                messagesLoaded = true; messagesEmpty = data.isEmpty();
                adapter.submit(data);
                if (data.isEmpty() && (room == null || !room.archived)) {
                    stateView.showEmpty(getString(R.string.chat_messages_empty_title),
                            getString(R.string.chat_messages_empty_message));
                } else {
                    stateView.hide();
                    scrollToLast();
                }
                if (isResumedNow()) repository.markRead(matchId);
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
        ChatComposer.bind(findViewById(R.id.chatComposer), room.archived, sending);
        findViewById(R.id.chatAiRow).setVisibility(room.archived ? View.GONE : View.VISIBLE);
        // 매칭 스냅샷(matches/{matchId})을 아직 못 받으면 확정 시간이 없다(BT-034 연결 전·문서 생성 전). 시간 카드를 숨긴다.
        boolean hasMatchTime = room.meetingDate != null && (room.appointments.isEmpty() || MatchAppointmentPolicy.representative(room.appointments, repository.nowMillis()) != null);
        findViewById(R.id.chatMatchInfo).setVisibility(active && hasMatchTime ? View.VISIBLE : View.GONE);
        findViewById(R.id.chatEndedBanner).setVisibility(active ? View.GONE : View.VISIBLE);
        if (room.archived && messagesLoaded && messagesEmpty) stateView.hide();
        if (active && hasMatchTime) bindMatchInfo();
        else bindReviewLink();
    }

    private void bindMatchInfo() {
        long now = repository.nowMillis();
        MatchAppointment appointment = MatchAppointmentPolicy.representative(room.appointments, now);
        ChatRoom display = appointment == null ? room : room.withDisplayAppointment(appointment);
        String time = ChatTimeFormatter.timeRange(display, now, getString(R.string.chat_today), getString(R.string.chat_yesterday));
        int additional = MatchAppointmentPolicy.additionalCount(room.appointments, now);
        ((TextView) findViewById(R.id.chatMatchTime)).setText(additional > 0 ? getString(R.string.appointment_additional, time, additional) : time);
        ((TextView) findViewById(R.id.chatMatchRemaining)).setText(appointment != null && appointment.startAtMillis() > now
                ? getString(R.string.appointment_upcoming) : getString(R.string.chat_remaining, ChatTimeFormatter.remaining(display, now)));
        boolean hasPlace = room.recommendedPlace != null;
        findViewById(R.id.chatPlaceRow).setVisibility(hasPlace ? View.VISIBLE : View.GONE);
        if (hasPlace) {
            ((TextView) findViewById(R.id.chatPlace)).setText(getString(R.string.chat_recommended_place,
                    room.recommendedPlace));
        }
    }

    // ---- 후기 ----

    private void refreshReviewStatus() {
        if (room == null || AuthRepositories.isFirebaseEnabled(this)) return; // 실제 후기는 BT-044에서 연결
        for (MatchAppointment item : MatchAppointmentPolicy.targets(room.appointments, MatchAppointmentPolicy.Action.REVIEW)) {
            reviewRepository.loadMyReviewStatus(item.matchId, new ReviewRepository.StatusCallback() {
                @Override public void onResult(boolean written) {
                    if (isFinishing() || isDestroyed()) return;
                    reviewStatuses.put(item.matchId, written);
                    if (room != null && room.status == ChatRoom.Status.ENDED) bindReviewLink();
                }
                @Override public void onError() {
                    if (isFinishing() || isDestroyed()) return;
                    reviewStatuses.remove(item.matchId);
                    if (room != null && room.status == ChatRoom.Status.ENDED) bindReviewLink();
                }
            });
        }
    }

    private void bindReviewLink() {
        TextView link = findViewById(R.id.chatReviewLink);
        List<MatchAppointment> targets = MatchAppointmentPolicy.targets(reviewAppointments(), MatchAppointmentPolicy.Action.REVIEW);
        link.setVisibility(room.archived || !targets.isEmpty() ? View.VISIBLE : View.GONE);
        link.setText(room.archived || targets.size() == 1 && targets.get(0).reviewWritten ? R.string.chat_review_view : R.string.chat_review_write);
    }

    private void openReview() {
        if (room == null) return;
        if (!MatchSelectionSheet.show(getSupportFragmentManager(), KEY_SELECT_REVIEW, MatchAppointmentPolicy.Action.REVIEW,
                reviewAppointments(), repository.nowMillis())) Toast.makeText(this, R.string.appointment_no_target, Toast.LENGTH_SHORT).show();
    }

    private List<MatchAppointment> reviewAppointments() {
        List<MatchAppointment> result = new ArrayList<>();
        if (room != null) for (MatchAppointment a : room.appointments) result.add(new MatchAppointment(a.matchId, a.chatRoomId, a.meetingDate, a.startMinute,
                a.endMinute, a.purpose, a.status, a.reviewEligible, reviewStatuses.getOrDefault(a.matchId, a.reviewWritten)));
        return result;
    }
    private void openSelectedReview(String id) {
        if (room == null || isFinishing() || isDestroyed()) return;
        MatchAppointment selected = MatchAppointmentPolicy.targets(reviewAppointments(), MatchAppointmentPolicy.Action.REVIEW).stream().filter(a -> a.matchId.equals(id)).findFirst().orElse(null);
        if (selected == null) return;
        if (AuthRepositories.isFirebaseEnabled(this)) { Toast.makeText(this, R.string.request_review_pending, Toast.LENGTH_SHORT).show(); return; }
        PartnerSummary partner = PartnerSummary.from(this, room.withDisplayAppointment(selected), repository.nowMillis());
        startActivity(selected.reviewWritten ? ReviewDetailActivity.intent(this, id, partner) : ReviewWriteActivity.intent(this, id, partner));
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
        if (!room.archived && !MatchAppointmentPolicy.targets(room.appointments, MatchAppointmentPolicy.Action.REVIEW).isEmpty()) {
            labels.add(getString(R.string.chat_review_write)); actions.add(this::openReview);
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
        if (room == null || room.archived) return;
        if (!MatchSelectionSheet.show(getSupportFragmentManager(), KEY_SELECT_END, MatchAppointmentPolicy.Action.END,
                room.appointments, repository.nowMillis())) Toast.makeText(this, R.string.appointment_no_target, Toast.LENGTH_SHORT).show();
    }
    private void confirmSelectedEnd(String id) {
        if (room == null) return;
        MatchAppointment selected = MatchAppointmentPolicy.targets(room.appointments, MatchAppointmentPolicy.Action.END).stream().filter(a -> a.matchId.equals(id)).findFirst().orElse(null);
        if (selected == null || getSupportFragmentManager().isStateSaved()) return;
        Bundle result = new Bundle(); result.putString(MatchSelectionSheet.MATCH_ID, id);
        ConfirmDialog.show(getSupportFragmentManager(), KEY_END_MATCH, getString(R.string.chat_end_title),
                MatchSelectionSheet.timeLabel(selected) + "\n" + getString(R.string.chat_end_message), getString(R.string.chat_end_confirm), getString(R.string.chat_close), result);
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

    private void endMatch(String selectedId) {
        if (actionInProgress || room == null || room.archived || selectedId == null
                || MatchAppointmentPolicy.targets(room.appointments, MatchAppointmentPolicy.Action.END).stream().noneMatch(a -> a.matchId.equals(selectedId))) return;
        actionInProgress = true;
        repository.endMatch(selectedId, new ChatRepository.ActionCallback() {
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
        if (replyRequest != null || room == null || room.archived) return;
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

    /** 응답이 올 때까지 전송 버튼을 잠가 중복 전송을 막는다. */
    private void send() {
        if (sending || room == null || room.archived) return;
        String text = input.getText().toString();
        if (text.trim().isEmpty()) return;
        setSending(true);
        repository.sendMessage(matchId, text, new ChatRepository.SendCallback() {
            @Override
            public void onSuccess() {
                if (isFinishing() || isDestroyed()) return;
                setSending(false);
                // 보내는 사이 사용자가 내용을 고쳤으면 지우지 않는다.
                if (text.equals(input.getText().toString())) input.setText("");
            }

            @Override
            public void onError() {
                if (isFinishing() || isDestroyed()) return;
                setSending(false);
                // 실패해도 작성한 내용은 입력창에 남겨 다시 보낼 수 있게 한다.
                Toast.makeText(ChatRoomActivity.this, R.string.chat_send_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setSending(boolean value) {
        sending = value;
        ChatComposer.bind(findViewById(R.id.chatComposer), room != null && room.archived, value);
    }

    private boolean isResumedNow() {
        return getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
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
