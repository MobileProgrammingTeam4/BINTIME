package com.team4.bintime.timetable;

import com.team4.bintime.R;
import com.team4.bintime.data.repository.TimetableImageFailure;

/** [BT-025] 이미지 준비·업로드 실패 코드 → 안내 문구 리소스. {@code getDetail()}은 보여 주지 않는다. */
final class TimetableUploadMessages {

    private TimetableUploadMessages() {
    }

    static int of(TimetableImageFailure.Code code) {
        if (code == null) return R.string.timetable_upload_error_unknown;
        switch (code) {
            case UNSUPPORTED_TYPE:
                return R.string.timetable_upload_error_unsupported;
            case SOURCE_TOO_LARGE:
                return R.string.timetable_upload_error_source_too_large;
            case CORRUPT:
                return R.string.timetable_upload_error_corrupt;
            case TOO_SMALL:
                return R.string.timetable_upload_error_too_small;
            case COMPRESSED_TOO_LARGE:
                return R.string.timetable_upload_error_compressed_too_large;
            case READ_FAILED:
                return R.string.timetable_upload_error_read_failed;
            case INVALID_COUNT:
                return R.string.timetable_upload_error_count;
            case NOT_SIGNED_IN:
                return R.string.timetable_upload_error_not_signed_in;
            case PERMISSION_DENIED:
                return R.string.timetable_upload_error_permission;
            case NETWORK:
                return R.string.timetable_upload_error_network;
            case QUOTA_EXCEEDED:
                return R.string.timetable_upload_error_quota;
            default:
                return R.string.timetable_upload_error_unknown;
        }
    }
}
