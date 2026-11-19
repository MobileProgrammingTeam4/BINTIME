package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.repository.MockTimetableImageRepositoryAccess;
import com.team4.bintime.data.repository.TimetableImageFailure.Code;
import com.team4.bintime.data.repository.TimetableImagePaths;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * [BT-025] 준비 → 업로드 → 정리 흐름. 준비 단계는 대기열 Executor로 원하는 시점에 실행해
 * 취소·선택 변경 뒤 늦게 도착한 결과를 시험한다.
 */
public class TimetableImageUploaderTest {

    private final Queue<Runnable> background = new ArrayDeque<>();
    private final Executor direct = Runnable::run;
    private final MockTimetableImageRepositoryAccess repo = new MockTimetableImageRepositoryAccess("uid-me");
    private final List<TimetableUploadState> states = new ArrayList<>();
    private final TimetableImageUploader uploader =
            new TimetableImageUploader(repo.repository(), background::add, direct, states::add);

    private static TimetableImageUploader.Preparer allOk() {
        return index -> TimetableImageCompressor.Result.ok(new byte[100 * (index + 1)]);
    }

    private void runBackground() {
        while (!background.isEmpty()) background.poll().run();
    }

    private TimetableUploadState last() {
        return states.get(states.size() - 1);
    }

    @Test
    public void twoImages_prepareUploadAndExposePathsInOrder() {
        uploader.start(2, allOk());
        assertEquals(TimetableUploadState.Phase.PREPARING, last().phase);
        assertTrue(last().isBusy());

        runBackground();

        TimetableUploadState done = last();
        assertEquals(TimetableUploadState.Phase.UPLOADED, done.phase);
        assertEquals(100, done.progressPercent);
        assertEquals(2, done.paths.size());
        for (int i = 0; i < 2; i++) {
            assertTrue(done.paths.get(i), done.paths.get(i).startsWith("timetableImages/uid-me/"));
            assertTrue(done.paths.get(i).endsWith("/" + i + ".jpg"));
        }
        assertEquals(done.paths, uploader.getUploadedPaths());
        assertEquals(done.paths, repo.storedPaths());
        // 진행률은 0에서 시작해 단조 증가한다.
        int previous = -1;
        for (TimetableUploadState s : states) {
            if (s.phase != TimetableUploadState.Phase.UPLOADING) continue;
            assertTrue(s.progressPercent >= previous);
            previous = s.progressPercent;
        }
        assertTrue(previous >= 0);
    }

    @Test
    public void badImages_allReportedAtOnce_nothingUploaded() {
        uploader.start(3, index -> index == 0
                ? TimetableImageCompressor.Result.ok(new byte[10])
                : TimetableImageCompressor.Result.fail(index == 1 ? Code.CORRUPT : Code.COMPRESSED_TOO_LARGE));
        runBackground();

        TimetableUploadState failed = last();
        assertEquals(TimetableUploadState.Phase.FAILED, failed.phase);
        assertEquals(Arrays.asList(null, Code.CORRUPT, Code.COMPRESSED_TOO_LARGE), failed.imageFailures);
        assertTrue(failed.hasImageFailures());
        assertFalse(failed.canRetrySameSelection());
        assertTrue(repo.storedPaths().isEmpty());
    }

    @Test
    public void preparedBytesAtUploadLimit_areRejectedBeforeUpload() {
        uploader.start(1, index -> TimetableImageCompressor.Result.ok(new byte[(int) TimetableImagePaths.MAX_UPLOAD_BYTES]));
        runBackground();
        assertEquals(Arrays.asList(Code.COMPRESSED_TOO_LARGE), last().imageFailures);
        assertTrue(repo.storedPaths().isEmpty());
    }

    @Test
    public void preparerCrashOrNull_isTreatedAsCorrupt() {
        uploader.start(2, index -> {
            if (index == 0) throw new IllegalStateException("decoder");
            return null;
        });
        runBackground();
        assertEquals(Arrays.asList(Code.CORRUPT, Code.CORRUPT), last().imageFailures);
    }

    @Test
    public void networkFailure_isRetryableAndNotBlamedOnImage() {
        repo.failNextUploadAt(1, Code.NETWORK);
        uploader.start(2, allOk());
        runBackground();

        TimetableUploadState failed = last();
        assertEquals(TimetableUploadState.Phase.FAILED, failed.phase);
        assertFalse(failed.hasImageFailures());
        assertTrue(failed.canRetrySameSelection());
        assertEquals(Code.NETWORK, failed.failure.getCode());
        assertTrue(repo.storedPaths().isEmpty());

        uploader.start(2, allOk());
        runBackground();
        assertEquals(TimetableUploadState.Phase.UPLOADED, last().phase);
    }

    @Test
    public void permissionDenied_isNotRetryable() {
        repo.failNextUploadAt(0, Code.PERMISSION_DENIED);
        uploader.start(1, allOk());
        runBackground();
        assertFalse(last().canRetrySameSelection());
        assertFalse(last().hasImageFailures());
    }

    @Test
    public void invalidCount_failsWithoutPreparing() {
        uploader.start(0, allOk());
        assertEquals(Code.INVALID_COUNT, last().failure.getCode());
        uploader.start(6, allOk());
        assertEquals(Code.INVALID_COUNT, last().failure.getCode());
        assertTrue(background.isEmpty());
    }

    @Test
    public void startWhileBusy_isIgnored() {
        uploader.start(2, allOk());
        uploader.start(2, allOk());
        assertEquals(1, background.size());
    }

    @Test
    public void discardAfterUpload_deletesImagesAndResets() {
        uploader.start(2, allOk());
        runBackground();
        assertEquals(2, repo.storedPaths().size());

        uploader.discard();

        assertEquals(TimetableUploadState.Phase.IDLE, last().phase);
        assertTrue(repo.storedPaths().isEmpty());
        assertTrue(uploader.getUploadedPaths().isEmpty());
    }

    @Test
    public void discardDuringPreparation_dropsLateResult() {
        uploader.start(2, allOk());
        uploader.discard();
        runBackground(); // 선택 변경 뒤 도착한 준비 결과

        assertEquals(TimetableUploadState.Phase.IDLE, last().phase);
        assertTrue(repo.storedPaths().isEmpty());
    }

    @Test
    public void restartWithNewSelection_deletesPreviousBatch() {
        uploader.start(2, allOk());
        runBackground();
        List<String> first = new ArrayList<>(uploader.getUploadedPaths());

        uploader.start(1, allOk());
        runBackground();

        List<String> stored = repo.storedPaths();
        assertEquals(1, stored.size());
        for (String old : first) assertFalse(stored.contains(old));
    }

    @Test
    public void notSignedIn_reportsGeneralFailure() {
        repo.setUid(null);
        uploader.start(1, allOk());
        runBackground();
        assertEquals(Code.NOT_SIGNED_IN, last().failure.getCode());
        assertNull(last().imageFailures.get(0));
    }
}
