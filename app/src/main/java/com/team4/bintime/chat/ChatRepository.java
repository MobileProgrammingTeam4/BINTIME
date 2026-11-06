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

    interface ActionCallback {
        void onSuccess();

        void onError();
    }

    /** ENDED 방에서도 전송할 수 있다. 공백만 있는 내용은 onError 로 거절한다. */
    void sendMessage(String matchId, String content, SendCallback callback);

    void markRead(String matchId);

    /**
     * 채팅 옵션의 수동 종료. ACTIVE → ENDED 로 바꾸고 확정 시간 스냅샷과 메시지는 유지한다.
     * 이미 ENDED 면 그대로 성공한다(멱등). 실제 구현은 D 의 매칭 종료 서버 함수(BT-038)를 호출한다.
     */
    void endMatch(String matchId, ActionCallback callback);

    /**
     * 채팅방 나가기. ENDED 에서만 허용하고 ACTIVE 면 onError 로 거절한다.
     * 내 목록에서만 숨기고 상대의 기록은 지우지 않는다. 실제 구현과 상대 안내는 BT-043 범위다.
     */
    void leaveRoom(String matchId, ActionCallback callback);
}
