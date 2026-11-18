package com.team4.bintime.chat;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/**
 * 채팅방 준비 저장소 선택. Firebase 가 설정되면 ensureMatchChat, 아니면 Mock(방은 항상 준비돼 있음)을 쓴다.
 */
public final class ChatLinkRepositories {

    /** Mock 모드의 방은 연결 단계가 없어 바로 준비 완료를 알린다. 콜백은 호출한 스레드에서 즉시 온다. */
    static final ChatLinkRepository MOCK = (matchId, callback) -> {
        callback.onResult(matchId, true);
        return () -> {};
    };

    private static FirebaseChatLinkRepository firebase;

    private ChatLinkRepositories() {}

    public static synchronized ChatLinkRepository get(Context context) {
        if (!AuthRepositories.isFirebaseEnabled(context)) return MOCK;
        if (firebase == null) firebase = new FirebaseChatLinkRepository();
        return firebase;
    }
}
