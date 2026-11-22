package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.R;
import com.team4.bintime.data.repository.TimetableImageFailure.Code;
import com.team4.bintime.data.repository.TimetableImagePaths;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** [BT-025] 이미지 개수·형식·크기·해상도 검사, 압축 품질 루프, 오류 문구 매핑. */
public class TimetableImageValidatorTest {

    @Test
    public void count_oneToFive() {
        assertEquals(Code.INVALID_COUNT, TimetableImageValidator.checkCount(0));
        assertNull(TimetableImageValidator.checkCount(1));
        assertNull(TimetableImageValidator.checkCount(2));
        assertNull(TimetableImageValidator.checkCount(5));
        assertEquals(Code.INVALID_COUNT, TimetableImageValidator.checkCount(6));
    }

    @Test
    public void type_allowsCommonImageFormats_unknownDeferredToDecode() {
        for (String ok : new String[]{"image/jpeg", "IMAGE/PNG", "image/webp", "image/heic", "image/heif", " image/jpg "}) {
            assertNull(ok, TimetableImageValidator.checkType(ok));
        }
        for (String bad : new String[]{"image/gif", "application/pdf", "video/mp4", "text/plain"}) {
            assertEquals(bad, Code.UNSUPPORTED_TYPE, TimetableImageValidator.checkType(bad));
        }
        assertNull(TimetableImageValidator.checkType(null));
        assertNull(TimetableImageValidator.checkType(""));
    }

    @Test
    public void sourceSize_boundary() {
        long max = TimetableImageValidator.MAX_SOURCE_BYTES;
        assertNull(TimetableImageValidator.checkSourceSize(0));
        assertNull(TimetableImageValidator.checkSourceSize(max));
        assertEquals(Code.SOURCE_TOO_LARGE, TimetableImageValidator.checkSourceSize(max + 1));
    }

    @Test
    public void dimensions_corruptAndTooSmall() {
        assertEquals(Code.CORRUPT, TimetableImageValidator.checkDimensions(0, 100));
        assertEquals(Code.CORRUPT, TimetableImageValidator.checkDimensions(-1, -1));
        int min = TimetableImageValidator.MIN_LONG_SIDE;
        assertEquals(Code.TOO_SMALL, TimetableImageValidator.checkDimensions(min - 1, 100));
        assertNull(TimetableImageValidator.checkDimensions(100, min));
        assertNull(TimetableImageValidator.checkDimensions(4000, 3000));
    }

    @Test
    public void uploadSize_strictlyBelowFiveMegabytes() {
        int limit = (int) TimetableImagePaths.MAX_UPLOAD_BYTES;
        assertNull(TimetableImageValidator.checkUploadSize(new byte[limit - 1]));
        assertEquals(Code.COMPRESSED_TOO_LARGE, TimetableImageValidator.checkUploadSize(new byte[limit]));
        assertEquals(Code.CORRUPT, TimetableImageValidator.checkUploadSize(new byte[0]));
        assertEquals(Code.CORRUPT, TimetableImageValidator.checkUploadSize(null));
    }

    @Test
    public void uploadLimit_perCount_keepsTotalWithinServerLimit() {
        long mib = 1024L * 1024;
        assertEquals(5 * mib, TimetableImageValidator.uploadLimit(1));
        assertEquals(5 * mib, TimetableImageValidator.uploadLimit(2));
        assertEquals(14 * mib / 3, TimetableImageValidator.uploadLimit(3));
        assertEquals(14 * mib / 4, TimetableImageValidator.uploadLimit(4));
        assertEquals(14 * mib / 5, TimetableImageValidator.uploadLimit(5));
        // 범위 밖이면 가장 엄격한 5장 기준
        assertEquals(14 * mib / 5, TimetableImageValidator.uploadLimit(0));
        assertEquals(14 * mib / 5, TimetableImageValidator.uploadLimit(6));
        for (int n = 1; n <= 5; n++) {
            assertTrue(n * (TimetableImageValidator.uploadLimit(n) - 1) <= TimetableImageValidator.MAX_TOTAL_UPLOAD_BYTES);
        }
    }

    @Test
    public void qualityLoop_usesGivenLimit_andFailsBelowQuality40() throws IOException {
        long limit = TimetableImageValidator.uploadLimit(5);
        // 품질 60에서 처음으로 상한 미만
        byte[] fits = TimetableImageCompressor.encodeUnderLimit(q -> new byte[q > 60 ? (int) limit : (int) limit - 1], limit);
        assertEquals(limit - 1, fits.length);
        // 품질 40에서도 상한 이상 → null(→ COMPRESSED_TOO_LARGE)
        assertNull(TimetableImageCompressor.encodeUnderLimit(q -> new byte[(int) limit], limit));
    }

    @Test
    public void qualityLoop_stopsAtFirstResultUnderLimit() throws IOException {
        List<Integer> tried = new ArrayList<>();
        // 품질 90·80은 상한 이상, 70에서 통과.
        byte[] result = TimetableImageCompressor.encodeUnderLimit(q -> {
            tried.add(q);
            return new byte[q >= 80 ? 100 : 50];
        }, 100);
        assertEquals(50, result.length);
        assertEquals(Arrays.asList(90, 80, 70), tried);
    }

    @Test
    public void qualityLoop_allOverLimit_returnsNull() throws IOException {
        List<Integer> tried = new ArrayList<>();
        assertNull(TimetableImageCompressor.encodeUnderLimit(q -> {
            tried.add(q);
            return new byte[100];
        }, 100));
        assertEquals(TimetableImageCompressor.QUALITIES.length, tried.size());
    }

    @Test
    public void qualityLoop_skipsFailedEncodes() throws IOException {
        byte[] result = TimetableImageCompressor.encodeUnderLimit(q -> q == 90 ? null : new byte[1], 100);
        assertEquals(1, result.length);
    }

    @Test
    public void sampleSize_keepsLongSideBelowTwiceMax() {
        assertEquals(1, TimetableImageCompressor.sampleSize(2048, 1000));
        assertEquals(1, TimetableImageCompressor.sampleSize(4095, 1000));
        assertEquals(2, TimetableImageCompressor.sampleSize(4096, 1000));
        assertEquals(4, TimetableImageCompressor.sampleSize(1000, 8192));
    }

    @Test
    public void everyFailureCodeHasMessage_specificOnesAreNotGeneric() {
        for (Code code : Code.values()) {
            TimetableUploadMessages.of(code); // 예외 없이 문구가 있어야 한다
        }
        for (Code code : new Code[]{Code.UNSUPPORTED_TYPE, Code.SOURCE_TOO_LARGE, Code.CORRUPT, Code.TOO_SMALL,
                Code.COMPRESSED_TOO_LARGE, Code.READ_FAILED, Code.NETWORK, Code.PERMISSION_DENIED,
                Code.QUOTA_EXCEEDED}) {
            assertNotEquals(code.name(), R.string.timetable_upload_error_unknown, TimetableUploadMessages.of(code));
        }
    }
}
