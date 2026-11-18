package com.team4.bintime.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [BT-025 P2] 실제 Storage 저장소의 순서·경로·취소·정리 로직. Storage 호출은 가짜 {@link FakeStore}로 바꿔 끼우고,
 * 업로드 완료·실패 시점은 테스트가 직접 정한다(실제 Firebase 호출 없음).
 */
public class FirebaseTimetableImageRepositoryTest {

    private static final String UID = "uid-p2";
    private static final String BATCH = "11111111-2222-3333-4444-555555555555";

    /** put 은 대기열에 두고 테스트가 끝낸다. delete 는 바로 끝내며 실패할 경로를 정할 수 있다. */
    private static final class FakeStore implements FirebaseTimetableImageRepository.Store {
        final List<String> putPaths = new ArrayList<>();
        final List<String> contentTypes = new ArrayList<>();
        final List<PutListener> pending = new ArrayList<>();
        final List<Boolean> canceled = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final Map<String, TimetableImageFailure.Code> deleteFailures = new HashMap<>();
        final Set<String> stored = new HashSet<>();

        @Override
        public Handle put(String path, byte[] jpeg, String contentType, PutListener listener) {
            int i = putPaths.size();
            putPaths.add(path);
            contentTypes.add(contentType);
            pending.add(listener);
            canceled.add(false);
            return () -> canceled.set(i, true);
        }

        @Override
        public void delete(String path, DeleteListener listener) {
            deleted.add(path);
            TimetableImageFailure.Code failure = deleteFailures.get(path);
            if (failure == null) stored.remove(path); // 없는 파일도 성공(멱등)
            listener.onDone(failure);
        }

        /** n번째 put 을 성공시킨다(서버에 저장됨). */
        void succeed(int n) {
            stored.add(putPaths.get(n));
            pending.get(n).onSuccess();
        }

        void fail(int n, TimetableImageFailure.Code code) {
            pending.get(n).onError(code);
        }
    }

    private static final class Recorder implements TimetableImageRepository.UploadListener {
        final List<String> events = new ArrayList<>();
        List<String> paths;
        TimetableImageFailure failure;

        @Override
        public void onProgress(int index, long sentBytes, long totalBytes) {
            events.add("progress:" + index + ":" + sentBytes + "/" + totalBytes);
        }

        @Override
        public void onSuccess(List<String> paths) {
            events.add("success");
            this.paths = paths;
        }

        @Override
        public void onError(TimetableImageFailure failure) {
            events.add("error:" + failure.getCode() + "@" + failure.getIndex());
            this.failure = failure;
        }

        long terminalCount() {
            return events.stream().filter(e -> e.equals("success") || e.startsWith("error")).count();
        }
    }

    private static final class Result implements TimetableImageRepository.Callback<Void> {
        int successes;
        TimetableImageFailure failure;

        @Override
        public void onSuccess(Void result) {
            successes++;
        }

        @Override
        public void onError(TimetableImageFailure failure) {
            this.failure = failure;
        }
    }

    private FakeStore store;
    private String uid;
    private List<TimetableImageFailure.Code> cleanupLogs;
    private FirebaseTimetableImageRepository repo;

    @Before
    public void setUp() {
        store = new FakeStore();
        uid = UID;
        cleanupLogs = new ArrayList<>();
        repo = new FirebaseTimetableImageRepository(store, () -> uid, cleanupLogs::add);
    }

    private static List<byte[]> jpegs(int count) {
        List<byte[]> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new byte[100 + i]);
        return list;
    }

    private static String path(int index) {
        return TimetableImagePaths.imagePath(UID, BATCH, index);
    }

    // ── 경로·순서 ─────────────────────────────────────────────

    @Test
    public void uploadsInOrder_onlyImagePaths_jpegContentType() {
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, jpegs(3), r);
        assertEquals("한 번에 하나씩 올린다", Collections.singletonList(path(0)), store.putPaths);
        store.pending.get(0).onProgress(50, 100);
        store.succeed(0);
        store.succeed(1);
        store.succeed(2);
        assertEquals(Arrays.asList(path(0), path(1), path(2)), store.putPaths);
        assertEquals("timetableImages/uid-p2/" + BATCH + "/2.jpg", path(2));
        for (String type : store.contentTypes) assertEquals("image/jpeg", type);
        assertEquals(Arrays.asList(path(0), path(1), path(2)), r.paths);
        assertEquals(1, r.terminalCount());
        assertTrue(r.events.contains("progress:0:50/100"));
        assertTrue(r.events.contains("progress:2:0/102"));
        assertTrue("성공하면 지우지 않는다", store.deleted.isEmpty());
    }

    @Test
    public void notSignedIn_noUpload() {
        uid = null;
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, jpegs(2), r);
        assertEquals(Collections.singletonList("error:NOT_SIGNED_IN@-1"), r.events);
        assertTrue(store.putPaths.isEmpty());
        uid = "bad/uid"; // 경로로 쓸 수 없는 UID
        repo.uploadBatch(BATCH, jpegs(2), new Recorder());
        assertTrue(store.putPaths.isEmpty());
    }

    @Test
    public void invalidInput_noUpload() {
        Recorder badBatch = new Recorder();
        repo.uploadBatch("../x", jpegs(1), badBatch);
        assertEquals("error:UNKNOWN@-1", badBatch.events.get(0));
        Recorder tooMany = new Recorder();
        repo.uploadBatch(BATCH, jpegs(6), tooMany);
        assertEquals("error:INVALID_COUNT@-1", tooMany.events.get(0));
        Recorder tooBig = new Recorder();
        repo.uploadBatch(BATCH, Arrays.asList(new byte[10], new byte[(int) TimetableImagePaths.MAX_UPLOAD_BYTES]),
                tooBig);
        assertEquals("error:COMPRESSED_TOO_LARGE@1", tooBig.events.get(0));
        assertTrue(store.putPaths.isEmpty());
    }

    // ── 실패 시 정리 ───────────────────────────────────────────

    @Test
    public void failureAtSecondImage_reportsOnce_thenDeletesAttemptedPaths() {
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, jpegs(3), r);
        store.succeed(0);
        store.fail(1, TimetableImageFailure.Code.NETWORK);
        assertEquals(1, r.terminalCount());
        assertEquals(TimetableImageFailure.Code.NETWORK, r.failure.getCode());
        assertEquals(1, r.failure.getIndex());
        assertTrue(r.failure.isRetryable());
        // 올라간 0번과 실패한 1번(서버에 남았을 수 있음)만 지운다. 시도하지 않은 2번은 올리지도 지우지도 않는다.
        assertEquals(Arrays.asList(path(0), path(1)), store.deleted);
        assertEquals(2, store.putPaths.size());
        assertTrue(store.stored.isEmpty());
        assertTrue(cleanupLogs.isEmpty());
    }

    @Test
    public void cleanupFailure_isOnlyLogged_notReportedAgain() {
        store.deleteFailures.put(path(0), TimetableImageFailure.Code.NETWORK);
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, jpegs(2), r);
        store.succeed(0);
        store.fail(1, TimetableImageFailure.Code.PERMISSION_DENIED);
        assertEquals(Collections.singletonList("error:PERMISSION_DENIED@1"),
                r.events.subList(r.events.size() - 1, r.events.size()));
        assertEquals(1, r.terminalCount());
        assertEquals(Collections.singletonList(TimetableImageFailure.Code.NETWORK), cleanupLogs);
    }

    @Test
    public void lateCallbacksAfterFailure_areIgnored() {
        Recorder r = new Recorder();
        repo.uploadBatch(BATCH, jpegs(2), r);
        store.fail(0, TimetableImageFailure.Code.NETWORK);
        int before = r.events.size();
        store.pending.get(0).onProgress(10, 100);
        store.pending.get(0).onSuccess();
        store.pending.get(0).onError(TimetableImageFailure.Code.UNKNOWN);
        assertEquals(before, r.events.size());
        assertEquals(1, store.putPaths.size());
    }

    // ── 취소 ─────────────────────────────────────────────────

    @Test
    public void cancel_midUpload_noCallbacks_deletesUploaded_cancelsInFlight() {
        Recorder r = new Recorder();
        TimetableImageRepository.Registration reg = repo.uploadBatch(BATCH, jpegs(3), r);
        store.succeed(0);
        int before = r.events.size();
        reg.cancel();
        reg.cancel(); // 여러 번 불러도 안전
        assertTrue("진행 중인 1번 업로드를 취소한다", store.canceled.get(1));
        assertEquals(Collections.singletonList(path(0)), store.deleted);
        store.fail(1, TimetableImageFailure.Code.CANCELED);
        store.pending.get(1).onProgress(1, 2);
        assertEquals("취소 뒤에는 콜백 없음", before, r.events.size());
        assertEquals(0, r.terminalCount());
        assertEquals("다음 이미지는 올리지 않는다", 2, store.putPaths.size());
    }

    @Test
    public void cancel_thenInFlightUploadFinishesAnyway_isDeleted() {
        Recorder r = new Recorder();
        TimetableImageRepository.Registration reg = repo.uploadBatch(BATCH, jpegs(2), r);
        reg.cancel();
        store.succeed(0); // 취소 직전에 서버에 다 올라간 경우
        assertEquals(Collections.singletonList(path(0)), store.deleted);
        assertTrue(store.stored.isEmpty());
        assertEquals(0, r.terminalCount());
        assertEquals(1, store.putPaths.size());
    }

    @Test
    public void cancelAfterSuccess_doesNothing() {
        Recorder r = new Recorder();
        TimetableImageRepository.Registration reg = repo.uploadBatch(BATCH, jpegs(1), r);
        store.succeed(0);
        reg.cancel();
        assertTrue("완료된 묶음은 호출한 쪽(Uploader)이 지운다", store.deleted.isEmpty());
        assertEquals(1, r.terminalCount());
    }

    @Test
    public void retryAfterFailure_usesNewBatchFolder() {
        String first = TimetableImagePaths.newBatchId();
        String second = TimetableImagePaths.newBatchId();
        assertNotEquals(first, second);
        repo.uploadBatch(first, jpegs(1), new Recorder());
        store.fail(0, TimetableImageFailure.Code.NETWORK);
        repo.uploadBatch(second, jpegs(1), new Recorder());
        assertEquals(TimetableImagePaths.imagePath(UID, second, 0), store.putPaths.get(1));
        assertNotEquals("같은 경로를 다시 쓰지 않는다(Rules 덮어쓰기 금지)", store.putPaths.get(0), store.putPaths.get(1));
    }

    // ── 삭제 ─────────────────────────────────────────────────

    @Test
    public void deleteBatch_deletesKnownPathsZeroToFour_missingIsSuccess() {
        Result result = new Result();
        repo.deleteBatch(BATCH, result);
        assertEquals(Arrays.asList(path(0), path(1), path(2), path(3), path(4)), store.deleted);
        assertEquals(1, result.successes);
        assertNull(result.failure);
    }

    @Test
    public void deleteBatch_invalidOrSignedOut_deletesNothing() {
        Result bad = new Result();
        repo.deleteBatch("a/b", bad);
        assertEquals(TimetableImageFailure.Code.UNKNOWN, bad.failure.getCode());
        uid = null;
        Result signedOut = new Result();
        repo.deleteBatch(BATCH, signedOut);
        assertEquals(TimetableImageFailure.Code.NOT_SIGNED_IN, signedOut.failure.getCode());
        repo.deleteBatch(BATCH, null); // 콜백 없어도 안전
        assertTrue(store.deleted.isEmpty());
    }

    @Test
    public void deleteBatch_partialFailure_reportsOnceAfterAll() {
        store.deleteFailures.put(path(2), TimetableImageFailure.Code.PERMISSION_DENIED);
        Result result = new Result();
        repo.deleteBatch(BATCH, result);
        assertEquals(5, store.deleted.size());
        assertEquals(0, result.successes);
        assertEquals(TimetableImageFailure.Code.PERMISSION_DENIED, result.failure.getCode());
        assertTrue("콜백이 있으면 로그 대신 호출한 쪽에 알린다", cleanupLogs.isEmpty());
    }

    @Test
    public void deleteImages_skipsOtherUsersPaths() {
        Result result = new Result();
        repo.deleteImages(Arrays.asList(path(0), "timetableImages/other/" + BATCH + "/0.jpg",
                "timetableImages/" + UID + "/../other/0.jpg", path(0)), result);
        assertEquals(Collections.singletonList(path(0)), store.deleted);
        assertEquals(1, result.successes);
        Result empty = new Result();
        repo.deleteImages(null, empty);
        assertEquals(1, empty.successes);
    }

    @Test
    public void deleteImages_withoutCallback_logsFailureOnly() {
        store.deleteFailures.put(path(0), TimetableImageFailure.Code.NETWORK);
        repo.deleteImages(Collections.singletonList(path(0)), null);
        assertEquals(Collections.singletonList(TimetableImageFailure.Code.NETWORK), cleanupLogs);
    }
}
