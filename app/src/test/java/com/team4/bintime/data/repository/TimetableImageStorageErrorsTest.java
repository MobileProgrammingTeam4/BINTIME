package com.team4.bintime.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.firebase.storage.StorageException;
import com.team4.bintime.data.repository.TimetableImageFailure.Code;

import org.junit.Test;

import java.io.IOException;
import java.net.UnknownHostException;

/** [BT-025 P2] StorageException 코드 → TimetableImageFailure.Code. */
public class TimetableImageStorageErrorsTest {

    @Test
    public void mapsEachStorageCode() {
        assertEquals(Code.PERMISSION_DENIED, TimetableImageStorageErrors.fromCode(StorageException.ERROR_NOT_AUTHORIZED, null));
        assertEquals(Code.NOT_SIGNED_IN, TimetableImageStorageErrors.fromCode(StorageException.ERROR_NOT_AUTHENTICATED, null));
        assertEquals(Code.NETWORK, TimetableImageStorageErrors.fromCode(StorageException.ERROR_RETRY_LIMIT_EXCEEDED, null));
        assertEquals(Code.CANCELED, TimetableImageStorageErrors.fromCode(StorageException.ERROR_CANCELED, null));
        assertEquals(Code.QUOTA_EXCEEDED, TimetableImageStorageErrors.fromCode(StorageException.ERROR_QUOTA_EXCEEDED, null));
        assertEquals(Code.UNKNOWN, TimetableImageStorageErrors.fromCode(StorageException.ERROR_UNKNOWN, null));
        assertEquals(Code.UNKNOWN, TimetableImageStorageErrors.fromCode(StorageException.ERROR_INVALID_CHECKSUM, null));
        assertEquals(Code.UNKNOWN, TimetableImageStorageErrors.fromCode(StorageException.ERROR_BUCKET_NOT_FOUND, null));
    }

    @Test
    public void unknownWithIoCause_isNetwork() {
        assertEquals(Code.NETWORK, TimetableImageStorageErrors.fromCode(StorageException.ERROR_UNKNOWN,
                new UnknownHostException("x")));
        assertEquals(Code.NETWORK, TimetableImageStorageErrors.fromCode(StorageException.ERROR_UNKNOWN,
                new RuntimeException(new IOException("x"))));
        assertEquals(Code.UNKNOWN, TimetableImageStorageErrors.fromCode(StorageException.ERROR_UNKNOWN,
                new IllegalStateException("x")));
        // 권한 거부는 원인이 무엇이든 권한 거부다.
        assertEquals(Code.PERMISSION_DENIED, TimetableImageStorageErrors.fromCode(StorageException.ERROR_NOT_AUTHORIZED,
                new IOException("x")));
    }

    @Test
    public void nonStorageException() {
        assertEquals(Code.UNKNOWN, TimetableImageStorageErrors.fromException(new IllegalStateException("x")));
        assertEquals(Code.UNKNOWN, TimetableImageStorageErrors.fromException(null));
        assertEquals(Code.NETWORK, TimetableImageStorageErrors.fromException(new IOException("x")));
        assertFalse(TimetableImageStorageErrors.isNotFound(new IllegalStateException("x")));
        assertFalse(TimetableImageStorageErrors.isNotFound(null));
    }

    @Test
    public void retryability() {
        assertTrue("RETRY_LIMIT_EXCEEDED → 네트워크 안내·다시 시도",
                TimetableImageFailure.of(TimetableImageStorageErrors.fromCode(
                        StorageException.ERROR_RETRY_LIMIT_EXCEEDED, null), "x").isRetryable());
        assertFalse("할당량 초과는 바로 다시 시도해도 소용없다",
                TimetableImageFailure.of(Code.QUOTA_EXCEEDED, "x").isRetryable());
        assertFalse(TimetableImageFailure.of(Code.PERMISSION_DENIED, "x").isRetryable());
    }
}
