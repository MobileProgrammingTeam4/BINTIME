package com.team4.bintime.chat;

import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * BT-010 UI 시연 전용 더미 답장. LLM 결과가 아니다. Firebase 를 설정하지 않은 Mock 모드에서만 쓴다(BT-041).
 * 채팅 데모 상태가 오류이면 실패를 돌려줘 대체 UI 를 확인할 수 있게 한다.
 */
public final class MockReplySuggestionRepository implements ReplySuggestionRepository {

    static final long DELAY_MILLIS = 600;
    private static final List<List<String>> MOCK_SETS = Arrays.asList(
            Arrays.asList("만나서 반가워요", "복지관 카페 어떠세요?", "자료구조 공부하시나요?"),
            Arrays.asList("좋아요! 그때 봐요", "조금 늦을 것 같아요", "도착하면 연락드릴게요"));

    private static MockReplySuggestionRepository shared;

    public static MockReplySuggestionRepository shared() {
        if (shared == null) {
            Handler handler = new Handler(Looper.getMainLooper());
            MockChatRepository chat = MockChatRepository.shared();
            shared = new MockReplySuggestionRepository(handler::postDelayed,
                    () -> chat.demoState() == MockChatRepository.DemoState.ERROR);
        }
        return shared;
    }

    private final MockChatRepository.Scheduler scheduler;
    private final BooleanSupplier failing;
    private int nextSet;

    MockReplySuggestionRepository(MockChatRepository.Scheduler scheduler, BooleanSupplier failing) {
        this.scheduler = scheduler;
        this.failing = failing;
    }

    @Override
    public Request requestReplies(String matchId, Callback callback) {
        boolean[] canceled = {false};
        scheduler.schedule(() -> {
            if (canceled[0]) return;
            if (matchId == null || failing.getAsBoolean()) {
                callback.onError(ReplySuggestionFailure.RETRY);
                return;
            }
            callback.onSuccess(MOCK_SETS.get(nextSet++ % MOCK_SETS.size()));
        }, DELAY_MILLIS);
        return () -> canceled[0] = true;
    }
}
