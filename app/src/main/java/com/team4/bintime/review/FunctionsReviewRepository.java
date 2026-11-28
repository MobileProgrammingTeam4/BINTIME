package com.team4.bintime.review;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.functions.HttpsCallableResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BT-044 submitReview·getMatchReview Callable(asia-northeast3). 앱은 matchId 와 작성 내용만 보낸다.
 * 작성자·상대 UID, 후기 자격, 상대 후기 공개 여부는 서버가 정한다. 호출 중 로그인 계정이 바뀌면 결과를 버린다.
 */
final class FunctionsReviewRepository implements ReviewRepository {

    private static final String REGION = "asia-northeast3";

    @Override
    public void loadMatchReview(String matchId, MatchReviewCallback callback) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            callback.onError(ReviewFailure.PERMISSION);
            return;
        }
        if (matchId == null || matchId.isEmpty()) {
            callback.onError(ReviewFailure.GENERIC);
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("matchId", matchId);
        call("getMatchReview", data).addOnCompleteListener(task -> {
            if (!Objects.equals(uid, FirebaseAuth.getInstance().getUid())) {
                callback.onError(ReviewFailure.PERMISSION);
                return;
            }
            if (!task.isSuccessful()) {
                callback.onError(failure(task.getException()));
                return;
            }
            MatchReview review = ReviewMapper.parse(task.getResult() == null ? null : task.getResult().getData(), uid);
            if (review == null) callback.onError(ReviewFailure.GENERIC);
            else callback.onResult(review);
        });
    }

    @Override
    public void submitReview(String matchId, Satisfaction satisfaction, Set<ReviewKeyword> keywords,
                             @Nullable String comment, SubmitCallback callback) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            callback.onError(ReviewFailure.PERMISSION);
            return;
        }
        if (matchId == null || satisfaction == null) {
            callback.onError(ReviewFailure.GENERIC);
            return;
        }
        call("submitReview", submitData(matchId, satisfaction, keywords, comment)).addOnCompleteListener(task -> {
            if (!Objects.equals(uid, FirebaseAuth.getInstance().getUid())) {
                callback.onError(ReviewFailure.PERMISSION);
                return;
            }
            if (task.isSuccessful()) callback.onSuccess();
            else callback.onError(failure(task.getException()));
        });
    }

    /** 키워드는 enum 이름(enum 순서), 한 줄 후기는 비었으면 null. 서버가 앞뒤 공백을 다시 정리한다. */
    static Map<String, Object> submitData(String matchId, Satisfaction satisfaction,
                                          @Nullable Set<ReviewKeyword> keywords, @Nullable String comment) {
        Map<String, Object> data = new HashMap<>();
        data.put("matchId", matchId);
        data.put("satisfaction", satisfaction.name());
        List<String> names = new ArrayList<>();
        if (keywords != null) {
            for (ReviewKeyword keyword : ReviewKeyword.values()) if (keywords.contains(keyword)) names.add(keyword.name());
        }
        data.put("keywords", names);
        String text = comment == null ? "" : comment.trim();
        data.put("comment", text.isEmpty() ? null : text);
        return data;
    }

    private static Task<HttpsCallableResult> call(String name, Map<String, Object> data) {
        return FirebaseFunctions.getInstance(REGION).getHttpsCallable(name).call(data);
    }

    private static ReviewFailure failure(@Nullable Exception error) {
        if (error instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException e = (FirebaseFunctionsException) error;
            return ReviewFailure.from(e.getCode().name(), e.getDetails());
        }
        return ReviewFailure.from(null, null);
    }
}
