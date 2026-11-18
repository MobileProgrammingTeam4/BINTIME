package com.team4.bintime.data.repository;

import java.util.Objects;

/**
 * [BT-025] 시간표 이미지 준비·업로드 실패. 화면은 {@link Code}로 문구를 고르고, {@link #getIndex()}로
 * 어떤 이미지인지 표시한다. {@link #getDetail()}은 테스트용 설명이며 URI·파일명·이미지 내용을 담지 않는다.
 */
public final class TimetableImageFailure {

    public enum Code {
        /** 로그인 UID가 없다. */
        NOT_SIGNED_IN,
        /** 선택 개수가 0이거나 {@link TimetableImagePaths#MAX_IMAGES}를 넘는다. */
        INVALID_COUNT,
        /** 지원하지 않는 파일 형식(JPG·PNG·WEBP·HEIC 외). */
        UNSUPPORTED_TYPE,
        /** 원본 파일이 너무 크다(압축 전 상한). */
        SOURCE_TOO_LARGE,
        /** 손상되었거나 이미지로 열 수 없다. */
        CORRUPT,
        /** 글자를 읽기 어려울 만큼 해상도가 낮다. */
        TOO_SMALL,
        /** 품질을 낮춰도 업로드 상한(5MB 미만)을 넘는다. */
        COMPRESSED_TOO_LARGE,
        /** 파일을 읽을 수 없다(권한 해제·삭제 등). */
        READ_FAILED,
        /** 서버 규칙이 거부했다(다른 계정 경로, 크기·형식 위반 등). */
        PERMISSION_DENIED,
        /** 네트워크 오류. 다시 시도할 수 있다. */
        NETWORK,
        /** 사용자가 취소했거나 화면이 종료됐다. */
        CANCELED,
        UNKNOWN
    }

    /** 특정 이미지와 무관한 실패의 index. */
    public static final int NO_INDEX = -1;

    private final Code code;
    private final int index;
    private final String detail;

    private TimetableImageFailure(Code code, int index, String detail) {
        this.code = Objects.requireNonNull(code, "code");
        this.index = index;
        this.detail = detail;
    }

    public static TimetableImageFailure of(Code code, String detail) {
        return new TimetableImageFailure(code, NO_INDEX, detail);
    }

    public static TimetableImageFailure atImage(Code code, int index, String detail) {
        return new TimetableImageFailure(code, index, detail);
    }

    public Code getCode() {
        return code;
    }

    /** 실패한 이미지의 선택 순서(0부터). 특정 이미지가 아니면 {@link #NO_INDEX}. */
    public int getIndex() {
        return index;
    }

    public String getDetail() {
        return detail;
    }

    /** 같은 선택으로 다시 시도해 볼 만한 실패인지(네트워크·알 수 없는 오류). */
    public boolean isRetryable() {
        return code == Code.NETWORK || code == Code.UNKNOWN;
    }

    @Override
    public String toString() {
        return "TimetableImageFailure{" + code + (index >= 0 ? ", index=" + index : "")
                + (detail != null ? ", " + detail : "") + "}";
    }
}
