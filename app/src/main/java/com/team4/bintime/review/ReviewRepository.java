package com.team4.bintime.review;

import androidx.annotation.Nullable;

import java.util.Set;

/**
 * 후기 데이터 경계(BT-044). Firebase 모드는 submitReview·getMatchReview Callable, Mock 모드는 메모리 시연 데이터를 쓴다.
 * reviews 는 앱이 직접 읽거나 쓰지 않는다(Rules 기본 거부). 작성 자격·중복·상대 후기 공개 조건은 서버가 검사한다.
 * 콜백은 메인 스레드에서 호출한다.
 */
public interface ReviewRepository {

    int COMMENT_MAX_LENGTH = 100;

    /** 받은 후기 공개 상태 */
    enum Visibility {
        /** 내 후기를 아직 쓰지 않아 볼 수 없다. */
        LOCKED,
        /** 내 후기는 썼지만 상대가 아직 쓰지 않았다. */
        NOT_YET,
        VISIBLE
    }

    interface MatchReviewCallback {
        void onResult(MatchReview review);

        void onError(ReviewFailure failure);
    }

    interface SubmitCallback {
        void onSuccess();

        void onError(ReviewFailure failure);
    }

    /** 개별 matchId 의 내 후기·받은 후기(공개 조건 적용)·작성 가능 여부 */
    void loadMatchReview(String matchId, MatchReviewCallback callback);

    /** 만족도는 필수, 키워드와 한 줄 후기는 선택이다. 같은 매칭에 두 번 작성할 수 없다. 상대 UID 는 서버가 정한다. */
    void submitReview(String matchId, Satisfaction satisfaction, Set<ReviewKeyword> keywords,
                      @Nullable String comment, SubmitCallback callback);
}
