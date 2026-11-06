package com.team4.bintime.review;

import androidx.annotation.StringRes;

import com.team4.bintime.R;

/** 후기 3단계 만족도. 저장 값은 enum 이름(BAD/GOOD/GREAT)을 쓴다. */
public enum Satisfaction {
    BAD(R.string.review_satisfaction_bad),
    GOOD(R.string.review_satisfaction_good),
    GREAT(R.string.review_satisfaction_great);

    @StringRes public final int label;

    Satisfaction(@StringRes int label) {
        this.label = label;
    }
}
