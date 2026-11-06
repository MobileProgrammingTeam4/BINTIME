package com.team4.bintime.chat;

import java.util.List;

/**
 * AI 답장 추천의 데이터 경계. BT-010 은 Mock 구현을 쓰고, BT-041 에서 Gemini(Cloud Functions 경유) 구현으로 교체한다.
 * 사용자가 반짝이 버튼을 눌렀을 때만 호출한다. 결과는 입력창에 넣을 제안일 뿐이며 자동 전송하지 않는다.
 * 콜백은 메인 스레드에서 호출한다.
 */
public interface ReplySuggestionRepository {

    /** 칩으로 보여줄 답장 수 */
    int REPLY_COUNT = 3;

    interface Callback {
        /** 비어 있지 않은 답장 {@link #REPLY_COUNT}개 */
        void onSuccess(List<String> replies);

        /** 시간 초과·형식 오류·쿼터 초과 등. 화면은 채팅을 그대로 쓸 수 있게 둔다. */
        void onError();
    }

    interface Request {
        /** 화면이 닫히면 결과를 전달하지 않는다. */
        void cancel();
    }

    Request requestReplies(String matchId, Callback callback);
}
