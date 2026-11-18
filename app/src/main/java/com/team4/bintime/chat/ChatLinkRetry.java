package com.team4.bintime.chat;

import androidx.annotation.Nullable;

/**
 * 한 매칭의 채팅방 준비 재시도 (BT-034 §3). 사용자가 누를 때만 호출하고 자동으로 반복하지 않는다.
 * <ul>
 *   <li>요청 중에는 다시 호출하지 않는다(중복 클릭).</li>
 *   <li>RETRY_LATER 는 서버가 준 retryAfterMillis 가 지나기 전 호출을 막는다.</li>
 *   <li>PAIR_BUSY 는 blockingMatchId 를 먼저 준비하고 원래 매칭을 한 번 더 준비한다. 다시 PAIR_BUSY 면 더 따라가지 않는다.</li>
 *   <li>PERMANENT·UNAUTHENTICATED 뒤에는 이 화면에서 더 호출하지 않는다(관리자 확인 대상).</li>
 *   <li>{@link #cancel()} 뒤에 도착한 결과는 버린다.</li>
 * </ul>
 * Firebase·Android 에 의존하지 않는다. 메인 스레드에서만 호출한다.
 */
final class ChatLinkRetry {

    interface Clock {
        long now();
    }

    interface Listener {
        void onPreparing();

        void onReady(String chatRoomId);

        /** 서버는 응답했지만 쓸 수 없는 방(보관 등). */
        void onUnavailable();

        /** retryAtMillis 는 다시 시도할 수 있는 시각(Clock 기준). PERMANENT·UNAUTHENTICATED 면 0. */
        void onFailed(ChatLinkFailure failure, long retryAtMillis);
    }

    private final ChatLinkRepository repository;
    private final Clock clock;
    private final String matchId;
    @Nullable private ChatLinkRepository.Request current;
    private long retryAtMillis;
    private boolean stopped, cancelled;
    /** 시도마다 증가. 이전 시도의 늦은 콜백을 구분한다. */
    private int attempt;

    ChatLinkRetry(ChatLinkRepository repository, Clock clock, String matchId) {
        this.repository = repository;
        this.clock = clock;
        this.matchId = matchId;
    }

    boolean inFlight() {
        return current != null;
    }

    /** 더 이상 시도할 수 없으면 true(영구 실패·로그인 만료·취소). */
    boolean stopped() {
        return stopped || cancelled;
    }

    long retryAtMillis() {
        return retryAtMillis;
    }

    boolean canStart() {
        return !stopped() && current == null && clock.now() >= retryAtMillis;
    }

    /** 시작했으면 true. 요청 중·대기 시간 전·중단 상태면 아무것도 하지 않고 false. */
    boolean start(Listener listener) {
        if (!canStart()) return false;
        int id = ++attempt;
        listener.onPreparing();
        call(id, matchId, false, listener);
        return true;
    }

    void cancel() {
        cancelled = true;
        if (current != null) current.cancel();
        current = null;
    }

    /**
     * 저장소가 ensure() 반환 전에 콜백을 바로 부를 수 있다(로그인 없음·Mock). 그때 반환값을 current 에 넣으면
     * 끝난 요청이 요청 중으로 남아 다시 시도할 수 없게 된다. 호출마다 완료 여부를 기록해 끝나지 않은 요청만 보관한다.
     */
    private void call(int id, String target, boolean chained, Listener listener) {
        boolean[] done = {false};
        ChatLinkRepository.Request request = repository.ensure(target, new ChatLinkRepository.Callback() {
            @Override
            public void onResult(@Nullable String chatRoomId, boolean chatReady) {
                if (done[0]) return; // 같은 호출의 중복 콜백
                done[0] = true;
                if (stale(id)) return;
                if (!target.equals(matchId)) {
                    // 막고 있던 매칭을 준비했다(성공 여부와 무관하게 잠금은 풀렸거나 그대로다). 원래 매칭을 한 번 더 준비한다.
                    call(id, matchId, true, listener);
                    return;
                }
                current = null;
                if (chatReady && chatRoomId != null && !chatRoomId.isEmpty()) listener.onReady(chatRoomId);
                else listener.onUnavailable();
            }

            @Override
            public void onFailure(ChatLinkFailure failure) {
                if (done[0]) return;
                done[0] = true;
                if (stale(id)) return;
                if (failure.kind == ChatLinkFailure.Kind.PAIR_BUSY) {
                    if (!chained && target.equals(matchId) && !matchId.equals(failure.blockingMatchId)) {
                        call(id, failure.blockingMatchId, true, listener);
                        return;
                    }
                    // 한 단계만 따라간다. 무한 연쇄 호출을 만들지 않는다.
                    failure = ChatLinkFailure.retryLater(ChatLinkFailure.DEFAULT_RETRY_MILLIS, failure.reason);
                }
                current = null;
                if (failure.kind == ChatLinkFailure.Kind.RETRY_LATER) {
                    retryAtMillis = clock.now() + failure.retryAfterMillis;
                    listener.onFailed(failure, retryAtMillis);
                } else {
                    stopped = true;
                    listener.onFailed(failure, 0);
                }
            }
        });
        // 이미 끝났으면(동기 콜백, 연쇄 호출이 current 를 새로 정한 경우 포함) 덮어쓰지 않는다.
        if (!done[0] && !stale(id)) current = request;
        else if (!done[0]) request.cancel(); // 반환 전에 취소·새 시도가 시작된 경우
    }

    private boolean stale(int id) {
        return cancelled || id != attempt;
    }
}
