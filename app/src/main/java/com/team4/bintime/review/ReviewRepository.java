package com.team4.bintime.review;

import androidx.annotation.Nullable;

import java.util.Set;

/**
 * 후기 데이터 경계. BT-010 은 Mock 구현을 쓰고, BT-044 에서 Firestore reviews 구현으로 교체한다.
 * 종료된 매칭 참여자만 작성할 수 있고, 내가 작성해야 상대 후기가 보이는 조건은 서버에서 검사한다.
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

    interface StatusCallback {
        void onResult(boolean myReviewWritten);

        void onError();
    }

    interface ReceivedCallback {
        /** VISIBLE 일 때만 review 가 있다. */
        void onResult(Visibility visibility, @Nullable Review review);

        void onError();
    }

    interface SubmitCallback {
        void onSuccess();

        /** 필수값 누락·길이 초과·중복 작성·네트워크 실패 */
        void onError();
    }

    void loadMyReviewStatus(String matchId, StatusCallback callback);

    void loadReceivedReview(String matchId, ReceivedCallback callback);

    /** 만족도는 필수, 키워드와 한 줄 후기는 선택이다. 같은 매칭에 두 번 작성할 수 없다. */
    void submitReview(String matchId, String targetUid, Satisfaction satisfaction,
                      Set<ReviewKeyword> keywords, @Nullable String comment, SubmitCallback callback);
}
