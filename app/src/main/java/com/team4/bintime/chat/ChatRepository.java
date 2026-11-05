package com.team4.bintime.chat;

import java.util.List;

/**
 * 채팅 화면이 쓰는 데이터 경계. BT-009 는 Mock 구현을 쓰고, BT-021·BT-022 에서 RTDB 구현으로 교체한다.
 * 위치와 이름은 BT-012 Repository 계약이 확정되면 맞춘다. 콜백은 메인 스레드에서 호출한다.
 */
public interface ChatRepository {

    interface Registration {
        void remove();
    }

    interface Listener<T> {
        void onData(T data);

        void onError();
    }

    interface SendCallback {
        void onSuccess();

        void onError();
    }

    String currentUid();

    /** 최근 메시지 순으로 전달한다. */
    Registration observeRooms(Listener<List<ChatRoom>> listener);

    Registration observeRoom(String matchId, Listener<ChatRoom> listener);

    /** 오래된 메시지부터 전달한다. */
    Registration observeMessages(String matchId, Listener<List<ChatMessage>> listener);

    /** ENDED 방에서도 전송할 수 있다. 공백만 있는 내용은 onError 로 거절한다. */
    void sendMessage(String matchId, String content, SendCallback callback);

    void markRead(String matchId);
}
