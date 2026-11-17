package com.team4.bintime.timetable;

import com.team4.bintime.data.repository.TimetableImageFailure.Code;
import com.team4.bintime.data.repository.TimetableImagePaths;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * [BT-025] 시간표 이미지 선택·원본 검사(압축 전). Android 의존성이 없어 JVM 단위 테스트로 검증한다.
 * 각 검사는 통과하면 null, 아니면 실패 코드를 돌려준다. 알 수 없는 값(MIME 없음, 크기 미상)은
 * 통과시키고 실제 디코딩 결과로 판단한다.
 */
final class TimetableImageValidator {

    /** 압축 전 원본 상한. 디코딩 메모리 보호용이며 업로드 상한(5MB 미만)은 압축 뒤에 따로 본다. */
    static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    /** 긴 변이 이보다 작으면 글자를 읽기 어렵다. */
    static final int MIN_LONG_SIDE = 320;

    private static final Set<String> SUPPORTED_TYPES = new HashSet<>(Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif"));

    private TimetableImageValidator() {
    }

    static Code checkCount(int count) {
        return count < 1 || count > TimetableImagePaths.MAX_IMAGES ? Code.INVALID_COUNT : null;
    }

    static Code checkType(String mimeType) {
        if (mimeType == null || mimeType.trim().isEmpty()) return null;
        String type = mimeType.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_TYPES.contains(type) ? null : Code.UNSUPPORTED_TYPE;
    }

    /** bytes가 0 이하이면 크기를 모르는 것으로 본다. */
    static Code checkSourceSize(long bytes) {
        return bytes > MAX_SOURCE_BYTES ? Code.SOURCE_TOO_LARGE : null;
    }

    static Code checkDimensions(int width, int height) {
        if (width <= 0 || height <= 0) return Code.CORRUPT;
        return Math.max(width, height) < MIN_LONG_SIDE ? Code.TOO_SMALL : null;
    }

    /** 압축 결과가 업로드 상한 미만인지. */
    static Code checkUploadSize(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return Code.CORRUPT;
        return jpeg.length >= TimetableImagePaths.MAX_UPLOAD_BYTES ? Code.COMPRESSED_TOO_LARGE : null;
    }
}
