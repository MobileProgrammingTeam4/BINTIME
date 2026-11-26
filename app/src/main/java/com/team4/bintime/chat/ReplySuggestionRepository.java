package com.team4.bintime.chat;

import java.util.List;

/**
 * AI 답장 추천의 데이터 경계. Firebase 모드는 suggestChatReplies Callable(Gemini, BT-041), 아니면 BT-010 Mock 을 쓴다
 * ({@link ReplySuggestionRepositories}).
 * 사용자가 반짝이 버튼을 눌렀을 때만 호출한다. 결과는 입력창에 넣을 제안일 뿐이며 자동 전송하지 않는다.
 * 콜백은 메인 스레드에서 호출한다.
 */
public interface ReplySuggestionRepository {

    /** 칩으로 보여줄 답장 수 */
    int REPLY_COUNT = 3;

    interface Callback {
        /** 비어 있지 않은 서로 다른 답장 {@link #REPLY_COUNT}개 */
        void onSuccess(List<String> replies);

        /** 화면은 칩을 숨기고 채팅을 그대로 쓸 수 있게 둔다. */
        void onError(ReplySuggestionFailure failure);
    }

    interface Request {
        /** 화면이 닫히면 결과를 전달하지 않는다. */
        void cancel();
    }

    /**
     * @param matchId 이 방의 개별 약속 ID(BT-034). 서버가 matches/{matchId}.chatRoomId 로 실제 방을 찾는다.
     */
    Request requestReplies(String matchId, Callback callback);
}
