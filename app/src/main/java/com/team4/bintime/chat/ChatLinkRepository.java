package com.team4.bintime.chat;

import androidx.annotation.Nullable;

/**
 * 확정 매칭의 채팅방 준비 요청(ensureMatchChat, BT-033 서버). 화면은 이 경계만 쓴다. 콜백은 메인 스레드.
 * 재시도 순서·간격은 {@link ChatLinkRetry} 가 정한다.
 */
public interface ChatLinkRepository {

    interface Request {
        /** 이후 도착한 결과는 버린다. */
        void cancel();
    }

    interface Callback {
        /** 서버 응답. chatReady=false 면 보관된 방 등 지금은 쓸 수 없는 방이다. */
        void onResult(@Nullable String chatRoomId, boolean chatReady);

        void onFailure(ChatLinkFailure failure);
    }

    Request ensure(String matchId, Callback callback);
}
