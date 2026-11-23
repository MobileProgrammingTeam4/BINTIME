package com.team4.bintime.timetable;

import com.team4.bintime.R;
import com.team4.bintime.data.recognition.RecognitionFailure;
import com.team4.bintime.data.recognition.RecognitionReview;

/**
 * [BT-026] 인식 실패 사유 → 안내 문구, 그리고 재시도 버튼을 보일지. 서버 사유 표(docs/BT-024.md "오류")를 따른다.
 * 어떤 실패든 시간표 등록은 수동 입력으로 계속할 수 있다({@link #offersManualInput}).
 */
final class RecognitionMessages {

    private RecognitionMessages() {
    }

    static int of(RecognitionFailure.Reason reason) {
        if (reason == null) return R.string.timetable_recognition_error_internal;
        switch (reason) {
            case UNAUTHENTICATED:
                return R.string.timetable_recognition_error_unauthenticated;
            case PERMISSION_DENIED:
                return R.string.timetable_recognition_error_permission;
            case QUOTA_EXCEEDED:
                return R.string.timetable_recognition_error_quota;
            case AI_RATE_LIMITED:
                return R.string.timetable_recognition_error_rate_limited;
            case AI_TIMEOUT:
                return R.string.timetable_recognition_error_timeout;
            case AI_INVALID_OUTPUT:
                return R.string.timetable_recognition_error_invalid_output;
            case AI_BLOCKED:
                return R.string.timetable_recognition_error_blocked;
            case AI_UNAVAILABLE:
                return R.string.timetable_recognition_error_unavailable;
            case AI_NOT_IMPLEMENTED:
                return R.string.timetable_recognition_error_not_implemented;
            case NETWORK:
                return R.string.timetable_recognition_error_network;
            case INVALID_INPUT:
                return R.string.timetable_recognition_error_invalid_input;
            case INTERNAL:
            default:
                return R.string.timetable_recognition_error_internal;
        }
    }

    /** 같은 이미지로 "다시 시도"를 보일지. 서버가 준 retryable 을 따른다. */
    static boolean showsRetry(RecognitionFailure failure) {
        return failure != null && failure.isRetryable();
    }

    /** 인식이 실패해도 수동 입력으로 이어 간다(BT-024 대체 흐름: 시간표 인식 실패 → 수동 입력). */
    static boolean offersManualInput(RecognitionFailure failure) {
        return failure != null;
    }

    /** 검수 행 판정 → 짧은 표시 문구. 표시 순서는 막는 것 먼저. */
    static int flag(RecognitionReview.Flag flag) {
        switch (flag) {
            case CONFLICT:
                return R.string.timetable_review_flag_conflict;
            case NEEDS_EDIT_NAME_EMPTY:
                return R.string.timetable_review_flag_name_empty;
            case NEEDS_EDIT_NAME_LENGTH:
                return R.string.timetable_review_flag_name_length;
            case NEEDS_EDIT_CLASSROOM_LENGTH:
                return R.string.timetable_review_flag_classroom_length;
            case NEEDS_EDIT_TIME:
                return R.string.timetable_review_flag_time;
            case NEEDS_EDIT_TOO_MANY_SLOTS:
                return R.string.timetable_review_flag_too_many_slots;
            case LOW_CONFIDENCE:
                return R.string.timetable_review_flag_low_confidence;
            case MERGED_DUPLICATE:
                return R.string.timetable_review_flag_merged;
            case WEEKEND:
                return R.string.timetable_review_flag_weekend;
            case NO_CLASSROOM:
            default:
                return R.string.timetable_review_flag_no_classroom;
        }
    }
}
