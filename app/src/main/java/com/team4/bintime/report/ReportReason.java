package com.team4.bintime.report;

import androidx.annotation.StringRes;

import com.team4.bintime.R;

/**
 * 신고 사유 (Figma 116:3693, 복수 선택). 저장 값은 enum 이름을 쓰고, 화면 문구는 strings_report.xml 에 둔다.
 * BT-051(B)에서 reports/{reportId}.reasons 의 허용 값으로 쓴다. 사유를 추가하면 서버 검증 목록도 함께 바꾼다.
 */
public enum ReportReason {
    NO_SHOW(R.string.report_reason_no_show),
    NO_CONTACT(R.string.report_reason_no_contact),
    ONE_SIDED_CANCEL(R.string.report_reason_one_sided_cancel),
    ABUSIVE_LANGUAGE(R.string.report_reason_abusive_language),
    INAPPROPRIATE_MEETING(R.string.report_reason_inappropriate_meeting),
    RELIGION_MLM_PROMOTION(R.string.report_reason_religion_mlm_promotion),
    INAPPROPRIATE_PROFILE(R.string.report_reason_inappropriate_profile),
    IMPERSONATION(R.string.report_reason_impersonation),
    SPAM(R.string.report_reason_spam);

    @StringRes public final int label;

    ReportReason(@StringRes int label) {
        this.label = label;
    }
}
