package com.team4.bintime.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** [BT-025] Mock 이미지 저장소: 동기 콜백, 경로·개수·크기 검사, 실패 시 부분 업로드 정리, 본인 경로만 삭제. */
public class MockTimetableImageRepositoryTest {

    private static final String BATCH = "batch-1";

    private String uid = "uid-me";
    private final MockTimetableImageRepository repo = new MockTimetableImageRepository(() -> uid);

    @Test
    public void upload_twoImages_succeedsInOrderWithProgress() {
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, Arrays.asList(new byte[10], new byte[20]), r);

        assertNull(r.failure);
        assertEquals(Arrays.asList("timetableImages/uid-me/batch-1/0.jpg", "timetableImages/uid-me/batch-1/1.jpg"),
                r.paths);
        assertEquals(Arrays.asList("0:0/10", "0:10/10", "1:0/20", "1:20/20"), r.progress);
        assertEquals(r.paths, repo.storedPaths());
    }

    @Test
    public void upload_notSignedIn_fails() {
        uid = null;
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, Collections.singletonList(new byte[1]), r);
        assertEquals(TimetableImageFailure.Code.NOT_SIGNED_IN, r.failure.getCode());
        assertTrue(repo.storedPaths().isEmpty());
    }

    @Test
    public void upload_rejectsBadCountSizeAndBatch() {
        Recorder none = new Recorder();
        repo.uploadBatch(BATCH, Collections.emptyList(), none);
        assertEquals(TimetableImageFailure.Code.INVALID_COUNT, none.failure.getCode());

        List<byte[]> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(new byte[1]);
        Recorder tooMany = new Recorder();
        repo.uploadBatch(BATCH, six, tooMany);
        assertEquals(TimetableImageFailure.Code.INVALID_COUNT, tooMany.failure.getCode());

        Recorder tooBig = new Recorder();
        repo.uploadBatch(BATCH, Arrays.asList(new byte[1], new byte[(int) TimetableImagePaths.MAX_UPLOAD_BYTES]), tooBig);
        assertEquals(TimetableImageFailure.Code.COMPRESSED_TOO_LARGE, tooBig.failure.getCode());
        assertEquals(1, tooBig.failure.getIndex());

        Recorder empty = new Recorder();
        repo.uploadBatch(BATCH, Collections.singletonList(new byte[0]), empty);
        assertEquals(TimetableImageFailure.Code.CORRUPT, empty.failure.getCode());

        Recorder badBatch = new Recorder();
        repo.uploadBatch("../x", Collections.singletonList(new byte[1]), badBatch);
        assertNotNull(badBatch.failure);

        assertTrue(repo.storedPaths().isEmpty());
    }

    @Test
    public void upload_justUnderLimit_isAccepted() {
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, Collections.singletonList(new byte[(int) TimetableImagePaths.MAX_UPLOAD_BYTES - 1]), r);
        assertNull(r.failure);
    }

    @Test
    public void upload_failureMidBatch_removesAlreadyUploaded() {
        repo.failNextUploadAt(1, TimetableImageFailure.Code.NETWORK);
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, Arrays.asList(new byte[1], new byte[1], new byte[1]), r);

        assertEquals(TimetableImageFailure.Code.NETWORK, r.failure.getCode());
        assertEquals(1, r.failure.getIndex());
        assertTrue(r.failure.isRetryable());
        assertNull(r.paths);
        assertTrue(repo.storedPaths().isEmpty());

        // 주입은 한 번만 적용된다.
        Recorder again = new Recorder();
        repo.uploadBatch(BATCH, Arrays.asList(new byte[1], new byte[1]), again);
        assertNull(again.failure);
    }

    @Test
    public void delete_removesOnlyOwnPaths_andIsIdempotent() {
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, Arrays.asList(new byte[1], new byte[1]), r);
        uid = "uid-other";
        repo.deleteImages(r.paths, null);
        assertEquals(2, repo.storedPaths().size());

        uid = "uid-me";
        boolean[] ok = {false};
        repo.deleteImages(r.paths, callback(ok));
        assertTrue(ok[0]);
        assertTrue(repo.storedPaths().isEmpty());

        ok[0] = false;
        repo.deleteImages(r.paths, callback(ok));
        assertTrue(ok[0]);
    }

    private static TimetableImageRepository.Callback<Void> callback(boolean[] ok) {
        return new TimetableImageRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                ok[0] = true;
            }

            @Override
            public void onError(TimetableImageFailure failure) {
                throw new AssertionError(failure.toString());
            }
        };
    }

    @Test
    public void deleteBatch_removesOnlyThatBatch_andIsIdempotent() {
        repo.uploadBatch(BATCH, Arrays.asList(new byte[1], new byte[2]), new Recorder());
        repo.uploadBatch("batch-2", Collections.singletonList(new byte[3]), new Recorder());
        int[] ok = {0};
        TimetableImageRepository.Callback<Void> callback = new TimetableImageRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                ok[0]++;
            }

            @Override
            public void onError(TimetableImageFailure failure) {
                throw new AssertionError(failure.toString());
            }
        };
        repo.deleteBatch(BATCH, callback);
        repo.deleteBatch(BATCH, callback); // 이미 없음 → 성공
        assertEquals(2, ok[0]);
        assertEquals(Collections.singletonList("timetableImages/uid-me/batch-2/0.jpg"), repo.storedPaths());
    }

    @Test
    public void deleteBatch_invalidOrSignedOut_fails() {
        repo.uploadBatch(BATCH, Collections.singletonList(new byte[1]), new Recorder());
        TimetableImageFailure[] got = {null};
        TimetableImageRepository.Callback<Void> callback = new TimetableImageRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                throw new AssertionError("success");
            }

            @Override
            public void onError(TimetableImageFailure failure) {
                got[0] = failure;
            }
        };
        repo.deleteBatch("../x", callback);
        assertEquals(TimetableImageFailure.Code.UNKNOWN, got[0].getCode());
        uid = null;
        repo.deleteBatch(BATCH, callback);
        assertEquals(TimetableImageFailure.Code.NOT_SIGNED_IN, got[0].getCode());
        uid = "uid-me";
        assertEquals(1, repo.storedPaths().size());
    }

    private static final class Recorder implements TimetableImageRepository.UploadListener {
        final List<String> progress = new ArrayList<>();
        List<String> paths;
        TimetableImageFailure failure;

        @Override
        public void onProgress(int index, long sentBytes, long totalBytes) {
            progress.add(index + ":" + sentBytes + "/" + totalBytes);
        }

        @Override
        public void onSuccess(List<String> paths) {
            this.paths = paths;
        }

        @Override
        public void onError(TimetableImageFailure failure) {
            this.failure = failure;
        }
    }
}
