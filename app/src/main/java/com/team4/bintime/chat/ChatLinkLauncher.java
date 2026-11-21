package com.team4.bintime.chat;

import android.content.Context;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

/**
 * 성사 화면·완료 카드 등 채팅 밖에서 매칭의 채팅방을 준비할 때 쓰는 진입점 (BT-034 §3, request/MatchChatNavigator).
 * 재시도 규칙은 {@link ChatLinkRetry} 를 그대로 쓰고, 계정+매칭마다 하나를 앱 프로세스 안에서 공유한다.
 * <ul>
 *   <li>같은 매칭을 여러 화면·여러 번 눌러도 요청은 동시에 하나만 보낸다(BUSY).</li>
 *   <li>서버가 준 retryAfterMillis 는 화면 회전·다른 화면에서도 지킨다(BUSY).</li>
 *   <li>CHAT_PAIR_BUSY 는 blockingMatchId 를 먼저 준비하고 원래 매칭을 한 번 더 준비한다.</li>
 *   <li>영구 오류 뒤에는 같은 계정·매칭으로 다시 호출하지 않는다. 로그인 만료는 다시 로그인하면 새로 시도한다.</li>
 * </ul>
 * 서버 ensureMatchChat 은 매칭을 만들지 않고, 같은 매칭은 같은 방으로 멱등 준비하므로 재시도로 매칭·방이 늘지 않는다.
 * 메인 스레드에서만 호출한다. 결과는 메인 스레드, Mock 저장소는 호출 중 바로 온다.
 */
public final class ChatLinkLauncher {

    public enum Failure {
        /** 이미 준비 중이거나 서버가 준 대기 시간 전. 호출하지 않았다. */
        BUSY,
        /** 일시 오류. 잠시 후 다시 누를 수 있다. */
        RETRY_LATER,
        /** 서버는 응답했지만 쓸 수 없는 방(보관됨). */
        ARCHIVED,
        /** 영구 불일치·권한 없음. 관리자 확인 대상. */
        PERMANENT,
        LOGIN_REQUIRED
    }

    public interface Listener {
        /** 실제로 서버를 부르기 시작할 때 한 번. BUSY·이미 중단된 매칭이면 오지 않는다. */
        void onPreparing();

        void onReady(String chatRoomId);

        void onFailed(Failure failure);
    }

    private static final class Entry {
        final ChatLinkRetry retry;
        Failure stoppedWith;

        Entry(ChatLinkRetry retry) {
            this.retry = retry;
        }
    }

    private static ChatLinkLauncher shared;

    private final ChatLinkRepository repository;
    private final ChatLinkRetry.Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    ChatLinkLauncher(ChatLinkRepository repository, ChatLinkRetry.Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public static synchronized ChatLinkLauncher get(Context context) {
        ChatLinkRepository repository = ChatLinkRepositories.get(context);
        if (shared == null || shared.repository != repository) {
            shared = new ChatLinkLauncher(repository, SystemClock::elapsedRealtime);
        }
        return shared;
    }

    /** 결과는 항상 listener 로 한 번 온다(onPreparing 뒤 onReady/onFailed, 또는 바로 onFailed). */
    public void start(String uid, String matchId, Listener listener) {
        String key = uid + "/" + matchId;
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry(new ChatLinkRetry(repository, clock, matchId));
            entries.put(key, entry);
        }
        if (entry.stoppedWith != null) {
            listener.onFailed(entry.stoppedWith);
            return;
        }
        Entry current = entry;
        boolean started = entry.retry.start(new ChatLinkRetry.Listener() {
            @Override
            public void onPreparing() {
                listener.onPreparing();
            }

            @Override
            public void onReady(String chatRoomId) {
                remove(key, current);
                listener.onReady(chatRoomId);
            }

            @Override
            public void onUnavailable() {
                remove(key, current);
                listener.onFailed(Failure.ARCHIVED);
            }

            @Override
            public void onFailed(ChatLinkFailure failure, long retryAtMillis) {
                Failure mapped = map(failure);
                // 영구 오류만 기억한다. 로그인 만료는 다시 로그인한 뒤 새로 시도할 수 있게 상태를 지운다.
                if (mapped == Failure.PERMANENT) current.stoppedWith = mapped;
                else if (mapped == Failure.LOGIN_REQUIRED) remove(key, current);
                listener.onFailed(mapped);
            }
        });
        if (!started) listener.onFailed(Failure.BUSY);
    }

    /** 성공·보관으로 끝난 매칭은 상태를 남기지 않는다. 이미 새 항목으로 바뀌었으면 건드리지 않는다. */
    private void remove(String key, Entry entry) {
        if (entries.get(key) == entry) entries.remove(key);
    }

    private static Failure map(ChatLinkFailure failure) {
        switch (failure.kind) {
            case PERMANENT:
                return Failure.PERMANENT;
            case UNAUTHENTICATED:
                return Failure.LOGIN_REQUIRED;
            default: // RETRY_LATER(PAIR_BUSY 는 ChatLinkRetry 가 한 단계 따라간 뒤 RETRY_LATER 로 바꾼다)
                return Failure.RETRY_LATER;
        }
    }
}
