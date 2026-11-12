package com.team4.bintime.timetable;

import com.team4.bintime.data.availability.Availability;
import com.team4.bintime.data.repository.TimetableFailure;

/**
 * [BT-015/016] 시간표 저장소 실패 코드 → 사용자 안내 문구. {@code getDetail()}은 보여주지 않는다.
 * 공용 strings.xml을 바꾸지 않도록 기존 시간표 화면처럼 문구를 코드에 둔다.
 */
final class TimetableMessages {

    static final String SAVING = "시간표를 저장하고 있어요.";
    static final String LOAD_FAILED = "시간표를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";
    static final String TOO_MANY_FREE_SLOTS =
            "매칭에 쓸 공강 구간은 " + Availability.MAX_SLOTS + "개까지 선택할 수 있어요.";

    private TimetableMessages() {
    }

    static String of(TimetableFailure failure) {
        if (failure == null) return "저장하지 못했어요. 다시 시도해 주세요.";
        switch (failure.getCode()) {
            case NOT_SIGNED_IN:
                return "로그인이 필요해요. 다시 로그인해 주세요.";
            case INVALID_COURSE:
                return "수업 정보를 다시 확인해 주세요.";
            case OVERLAP:
                return "같은 요일에 시간이 겹치는 수업이 있어요.";
            case INVALID_FREE_SLOT:
                return "수업과 겹치는 공강이 있어요. 공강을 다시 선택해 주세요.";
            case TOO_MANY_FREE_SLOTS:
                return TOO_MANY_FREE_SLOTS;
            case NOT_FOUND:
                return "수업을 찾지 못했어요. 다시 시도해 주세요.";
            case PERMISSION_DENIED:
                return "저장 권한이 없어요. 다시 로그인해 주세요.";
            case NETWORK:
                return "네트워크 연결을 확인한 뒤 다시 시도해 주세요.";
            case CONFLICT:
                return "다른 기기에서 시간표가 바뀌었어요. 다시 시도해 주세요.";
            default:
                return "저장하지 못했어요. 다시 시도해 주세요.";
        }
    }
}
