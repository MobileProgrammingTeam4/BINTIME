package com.team4.bintime.review;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;

/** Firebase 가 설정되면 Callable 구현, 아니면 Mock 시연 구현(BT-044). */
public final class ReviewRepositories {

    private static FunctionsReviewRepository firebase;

    private ReviewRepositories() {}

    public static synchronized ReviewRepository get(Context context) {
        if (!AuthRepositories.isFirebaseEnabled(context)) return MockReviewRepository.shared();
        if (firebase == null) firebase = new FunctionsReviewRepository();
        return firebase;
    }
}
