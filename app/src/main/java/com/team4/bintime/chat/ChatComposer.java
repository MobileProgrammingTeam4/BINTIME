package com.team4.bintime.chat;

import android.view.View;
import android.widget.EditText;
import com.team4.bintime.R;

/** 기존 MessageComposer의 입력 가능/기록 열람/전송 중 상태. 모양과 버튼을 복제하지 않는다. */
public final class ChatComposer {
    private ChatComposer() {}
    public static void bind(View composer, boolean readOnly, boolean sending) {
        EditText input = composer.findViewById(R.id.chatInput);
        input.setEnabled(!readOnly);
        input.setFocusableInTouchMode(!readOnly);
        input.setHint(readOnly ? R.string.chat_archive_read_only : R.string.chat_message_hint);
        composer.findViewById(R.id.chatSend).setEnabled(!readOnly && !sending);
        // 파일 첨부는 아직 연결되지 않아 기존 상태를 유지한다.
        composer.findViewById(R.id.chatAttach).setEnabled(false);
        composer.setAlpha(readOnly ? 0.65f : 1f);
    }
}
