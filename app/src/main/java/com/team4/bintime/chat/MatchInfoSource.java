package com.team4.bintime.chat;

/**
 * 채팅방의 매칭 스냅샷 출처. 실제 구현은 Firestore matches/{matchId} 를 구독한다(BT-034, D 의 BT-031 이후).
 * 그 전에는 {@link #UNAVAILABLE} 을 써서 시간 정보 없이 RTDB 값만으로 채팅방을 만든다.
 */
public interface MatchInfoSource {

    /** 매칭 문서가 아직 없거나 읽을 수 없으면 null 을 전달한다. 콜백은 메인 스레드. */
    ChatRepository.Registration observe(String matchId, ChatRepository.Listener<MatchInfo> listener);

    /** BT-034 전 기본값. 바로 null 을 한 번 전달한다. */
    MatchInfoSource UNAVAILABLE = (matchId, listener) -> {
        listener.onData(null);
        return () -> {};
    };
}
