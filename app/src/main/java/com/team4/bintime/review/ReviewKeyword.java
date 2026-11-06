package com.team4.bintime.review;

import androidx.annotation.StringRes;

import com.team4.bintime.R;

/**
 * 후기 키워드 (Figma 116:3395, 복수 선택). 저장 값은 enum 이름을 쓰고, 화면 문구는 strings_review.xml 에 둔다.
 * 자주 받은 키워드 TOP 집계(P2)도 이 코드를 기준으로 한다.
 */
public enum ReviewKeyword {
    ON_TIME(R.string.review_keyword_on_time),
    QUICK_REPLY(R.string.review_keyword_quick_reply),
    CLEAN_PARTING(R.string.review_keyword_clean_parting),
    KIND_MANNER(R.string.review_keyword_kind_manner),
    FOCUSED_STUDY(R.string.review_keyword_focused_study),
    QUIET_TOGETHER(R.string.review_keyword_quiet_together),
    MAJOR_TALK(R.string.review_keyword_major_talk),
    GOOD_CONVERSATION(R.string.review_keyword_good_conversation),
    COMFORTABLE_MOOD(R.string.review_keyword_comfortable_mood),
    CAMPUS_TIPS(R.string.review_keyword_campus_tips);

    @StringRes public final int label;

    ReviewKeyword(@StringRes int label) {
        this.label = label;
    }
}
