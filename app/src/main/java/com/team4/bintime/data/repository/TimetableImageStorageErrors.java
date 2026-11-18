package com.team4.bintime.data.repository;

import com.google.firebase.storage.StorageException;

import java.io.IOException;

/**
 * [BT-025 P2] Cloud Storage 오류 → {@link TimetableImageFailure.Code}. 판단은 오류 코드(int)와 원인으로만 하는
 * 순수 함수라 JVM 단위 테스트로 확인한다({@code StorageException.ERROR_*}는 컴파일 타임 상수).
 * <ul>
 *   <li>{@code ERROR_RETRY_LIMIT_EXCEEDED}는 네트워크로 본다. 업로드 재시도 상한은 #101(BT-030)과 같은 20초다
 *       ({@link FirebaseTimetableImageStore#UPLOAD_RETRY_MILLIS}).</li>
 *   <li>{@code ERROR_NOT_AUTHORIZED}: Rules 거부(다른 계정 경로, 5MB 이상, image/jpeg 아님, 같은 경로 덮어쓰기).</li>
 *   <li>{@code ERROR_QUOTA_EXCEEDED}: 버킷 할당량 초과. 파일 하나의 크기 초과가 아니다.</li>
 * </ul>
 */
final class TimetableImageStorageErrors {

    private TimetableImageStorageErrors() {
    }

    /** Storage 작업 실패 예외 → 코드. StorageException 이 아니면 UNKNOWN. */
    static TimetableImageFailure.Code fromException(Exception e) {
        if (e instanceof StorageException) {
            return fromCode(((StorageException) e).getErrorCode(), e.getCause());
        }
        return hasIoCause(e) ? TimetableImageFailure.Code.NETWORK : TimetableImageFailure.Code.UNKNOWN;
    }

    static TimetableImageFailure.Code fromCode(int errorCode, Throwable cause) {
        switch (errorCode) {
            case StorageException.ERROR_NOT_AUTHORIZED:
                return TimetableImageFailure.Code.PERMISSION_DENIED;
            case StorageException.ERROR_NOT_AUTHENTICATED:
                return TimetableImageFailure.Code.NOT_SIGNED_IN;
            case StorageException.ERROR_RETRY_LIMIT_EXCEEDED:
                return TimetableImageFailure.Code.NETWORK;
            case StorageException.ERROR_CANCELED:
                return TimetableImageFailure.Code.CANCELED;
            case StorageException.ERROR_QUOTA_EXCEEDED:
                return TimetableImageFailure.Code.QUOTA_EXCEEDED;
            default:
                return hasIoCause(cause) ? TimetableImageFailure.Code.NETWORK : TimetableImageFailure.Code.UNKNOWN;
        }
    }

    /** 지울 파일이 이미 없다. 삭제는 멱등이라 성공으로 본다. */
    static boolean isNotFound(Exception e) {
        return e instanceof StorageException
                && ((StorageException) e).getErrorCode() == StorageException.ERROR_OBJECT_NOT_FOUND;
    }

    private static boolean hasIoCause(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof IOException) return true;
        }
        return false;
    }
}
