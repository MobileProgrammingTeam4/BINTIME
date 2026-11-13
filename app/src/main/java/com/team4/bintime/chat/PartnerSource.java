package com.team4.bintime.chat;

/**
 * 채팅 상대 프로필 출처. 실제 구현은 B 의 ProfileRepository(publicProfiles)를 쓴다(#79 병합 후).
 * 그 전에는 {@link #UNAVAILABLE} 을 써서 화면이 기본 이름을 표시한다.
 */
public interface PartnerSource {

    /** 프로필이 없거나 읽을 수 없으면 null 을 전달한다. 콜백은 메인 스레드. */
    ChatRepository.Registration observe(String uid, ChatRepository.Listener<PartnerInfo> listener);

    /** #79 전 기본값. 바로 null 을 한 번 전달한다. */
    PartnerSource UNAVAILABLE = (uid, listener) -> {
        listener.onData(null);
        return () -> {};
    };
}
