package com.team4.bintime.data.repository;

import java.util.Objects;

/**
 * [BT-015] 시간표 저장소 실패 정보. 화면은 {@link Code}로 안내 문구를 고른다.
 * {@link #getDetail()}은 로그·테스트용 설명이며 사용자에게 그대로 보여주지 않는다.
 */
public final class TimetableFailure {

    public enum Code {
        /** 로그인 UID가 없다. */
        NOT_SIGNED_IN,
        /** 필드 형식·길이·시간 범위가 잘못됐다. */
        INVALID_COURSE,
        /** 같은 요일에 다른 수업과 시간이 겹친다. {@link #getConflictingCourseId()} 참고. */
        OVERLAP,
        /** [BT-016] 공강 선택이 30분 칸에 맞지 않거나 수업과 겹친다. */
        INVALID_FREE_SLOT,
        /** [BT-016] 매칭 대상 공강 구간이 상한(Availability.MAX_SLOTS)을 넘는다. */
        TOO_MANY_FREE_SLOTS,
        /** 수정하려는 수업이 없다. */
        NOT_FOUND,
        /** 서버 규칙이 거부했다. */
        PERMISSION_DENIED,
        /** 네트워크 오류 또는 오프라인. 트랜잭션은 오프라인에서 실패한다. */
        NETWORK,
        /** 다른 기기에서 먼저 수정해 revision이 바뀌었다. 최신 목록으로 다시 시도한다. */
        CONFLICT,
        UNKNOWN
    }

    private final Code code;
    private final String conflictingCourseId;
    private final String detail;

    private TimetableFailure(Code code, String conflictingCourseId, String detail) {
        this.code = Objects.requireNonNull(code, "code");
        this.conflictingCourseId = conflictingCourseId;
        this.detail = detail;
    }

    public static TimetableFailure of(Code code, String detail) {
        return new TimetableFailure(code, null, detail);
    }

    public static TimetableFailure overlap(String conflictingCourseId) {
        return new TimetableFailure(Code.OVERLAP, conflictingCourseId,
                "overlaps course " + conflictingCourseId);
    }

    public Code getCode() {
        return code;
    }

    /** OVERLAP일 때 겹치는 기존 수업 ID. 그 외에는 null. */
    public String getConflictingCourseId() {
        return conflictingCourseId;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "TimetableFailure{" + code
                + (conflictingCourseId != null ? ", conflict=" + conflictingCourseId : "")
                + (detail != null ? ", " + detail : "") + "}";
    }
}
