package com.team4.bintime.chat;

import android.view.View;
import android.widget.EditText;
import com.team4.bintime.R;

/** 기존 MessageComposer의 입력 가능/기록 열람/전송 중 상태. 모양과 버튼을 복제하지 않는다. */
public final class ChatComposer {
    private ChatComposer() {}
    public static void bind(View composer, boolean readOnly, boolean sending) {
        bind(composer, readOnly, true, sending);
    }
    public static void bind(View composer, boolean readOnly, boolean ready, boolean sending) {
        bind(composer, readOnly, ready, sending, ChatRoom.BlockState.NONE);
    }
    /** BT-046: 차단된 방은 기록 열람과 같은 비활성 입력창에 차단 안내만 다르게 보여 준다. */
    public static void bind(View composer, boolean readOnly, boolean ready, boolean sending, ChatRoom.BlockState block) {
        boolean blocked = block != null && block != ChatRoom.BlockState.NONE;
        boolean disabled = readOnly || !ready || blocked;
        EditText input = composer.findViewById(R.id.chatInput);
        input.setEnabled(!disabled);
        input.setFocusableInTouchMode(!disabled);
        input.setHint(readOnly ? R.string.chat_archive_read_only
                : block == ChatRoom.BlockState.BY_ME ? R.string.chat_blocked_by_me
                : block == ChatRoom.BlockState.BY_PARTNER ? R.string.chat_blocked_by_partner
                : ready ? R.string.chat_message_hint : R.string.chat_link_preparing);
        composer.findViewById(R.id.chatSend).setEnabled(!disabled && !sending);
        // 파일 첨부는 아직 연결되지 않아 기존 상태를 유지한다.
        composer.findViewById(R.id.chatAttach).setEnabled(false);
        composer.setAlpha(disabled ? 0.65f : 1f);
    }
}
