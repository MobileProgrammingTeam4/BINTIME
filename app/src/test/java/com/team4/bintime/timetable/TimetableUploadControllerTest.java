package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.recognition.MockTimetableRecognitionRepositoryAccess;
import com.team4.bintime.data.recognition.RecognitionFailure;
import com.team4.bintime.data.recognition.RecognitionResult;
import com.team4.bintime.data.recognition.TimetableRecognitionRepository;
import com.team4.bintime.data.repository.MockTimetableImageRepositoryAccess;
import com.team4.bintime.data.repository.TimetableImageFailure.Code;
import com.team4.bintime.data.repository.TimetableImagePaths;
import com.team4.bintime.data.repository.TimetableImageRepository;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * [BT-025] 선택 → 선택 시 검사 → 분석 가능 판정 → 업로드. AVD 버그(오류 이미지가 있는데 분석 버튼이 켜져 있고,
 * 누르면 업로드·검수로 넘어감)를 화면 없이 재현·방지한다. 이미지 키는 Uri 대신 문자열을 쓴다.
 */
public class TimetableUploadControllerTest {

    private final Queue<Runnable> background = new ArrayDeque<>();
    private final Executor direct = Runnable::run;
    private final MockTimetableImageRepositoryAccess repo = new MockTimetableImageRepositoryAccess("uid-me");
    private final MockTimetableRecognitionRepositoryAccess recognizer =
            new MockTimetableRecognitionRepositoryAccess("uid-me");
    /** 선택 시 검사 결과. 없으면 통과. */
    private final Map<String, Code> inspectResults = new HashMap<>();
    private final List<String> inspected = new ArrayList<>();
    private final List<String> prepared = new ArrayList<>();
    private final List<TimetableUploadScreen> screens = new ArrayList<>();
    /** 압축기에 넘긴 한 장 상한. */
    private final List<Long> limits = new ArrayList<>();
    /** 삭제 호출 기록("images:2", "batch:{id}"). 중복 삭제 확인용. */
    private final List<String> deletes = new ArrayList<>();
    private final TimetableImageRepository images = new CountingImages(repo.repository(), deletes);

    private final TimetableUploadController<String> controller = new TimetableUploadController<>(
            images, recognizer.repository(), background::add, direct,
            image -> {
                inspected.add(image);
                return inspectResults.get(image);
            },
            (image, limit) -> {
                prepared.add(image);
                limits.add(limit);
                return TimetableImageCompressor.Result.ok(new byte[100]);
            },
            screens::add);

    /** Mock 이미지 저장소에 위임하면서 삭제 호출을 기록한다. */
    private static final class CountingImages implements TimetableImageRepository {
        private final TimetableImageRepository delegate;
        private final List<String> deletes;

        CountingImages(TimetableImageRepository delegate, List<String> deletes) {
            this.delegate = delegate;
            this.deletes = deletes;
        }

        @Override
        public Registration uploadBatch(String batchId, List<byte[]> jpegs, UploadListener listener) {
            return delegate.uploadBatch(batchId, jpegs, listener);
        }

        @Override
        public void deleteImages(List<String> paths, Callback<Void> callback) {
            deletes.add("images:" + paths.size());
            delegate.deleteImages(paths, callback);
        }

        @Override
        public void deleteBatch(String batchId, Callback<Void> callback) {
            deletes.add("batch:" + batchId);
            delegate.deleteBatch(batchId, callback);
        }
    }

    /** 결과를 직접 돌려주는 비동기 인식 저장소(실제 Callable 처럼 나중에 끝난다). */
    private static final class AsyncRecognizer implements TimetableRecognitionRepository {
        final List<Callback> callbacks = new ArrayList<>();
        int cancels;

        @Override
        public Registration analyze(List<String> imagePaths, Callback callback) {
            callbacks.add(callback);
            return () -> cancels++;
        }
    }

    private void runBackground() {
        while (!background.isEmpty()) background.poll().run();
    }

    private TimetableUploadScreen last() {
        return screens.get(screens.size() - 1);
    }

    // ── AVD 버그 재현: 25MB 원본 1장 ──────────────────────────────────────────

    @Test
    public void oversizedSource_marksImage_disablesAnalyze_andAnalyzeIsIgnored() {
        inspectResults.put("big", Code.SOURCE_TOO_LARGE);
        controller.select(Collections.singletonList("big"));
        runBackground();

        TimetableUploadScreen screen = last();
        assertEquals(Arrays.asList(Code.SOURCE_TOO_LARGE), screen.imageFailures);
        assertTrue(screen.hasImageFailures());
        assertFalse(screen.canAnalyze);

        // 버튼이 눌리거나 시작 요청이 와도 압축기·업로더로 넘어가지 않는다.
        assertEquals(TimetableUploadController.AnalyzeResult.IGNORED, controller.analyze());
        runBackground();
        assertTrue(prepared.isEmpty());
        assertTrue(repo.storedPaths().isEmpty());
        assertNull(controller.takeRecognition());
        assertEquals(TimetableUploadState.Phase.IDLE, last().upload.phase);
    }

    @Test
    public void oneBadImageAmongGood_blocksWholeSelection() {
        inspectResults.put("b", Code.CORRUPT);
        controller.select(Arrays.asList("a", "b", "c"));
        runBackground();

        assertEquals(Arrays.asList(null, Code.CORRUPT, null), last().imageFailures);
        assertFalse(last().canAnalyze);
        assertEquals(TimetableUploadController.AnalyzeResult.IGNORED, controller.analyze());
        assertTrue(prepared.isEmpty());
    }

    @Test
    public void analyzeIsDisabledUntilSelectionChecksFinish() {
        controller.select(Arrays.asList("a", "b"));
        assertTrue(last().checking);
        assertFalse(last().canAnalyze);
        assertEquals(TimetableUploadController.AnalyzeResult.IGNORED, controller.analyze());

        runBackground();
        assertFalse(last().checking);
        assertTrue(last().canAnalyze);
    }

    // ── 기존 동작 유지 ─────────────────────────────────────────────────────

    @Test
    public void oneToFiveGoodImages_canAnalyze_andUploadInSelectionOrder() {
        // 장수 규칙 1~5장(결정). 이슈의 "2장 이상"은 여러 장 업로드 완료 조건.
        for (int n = 1; n <= 5; n++) {
            List<String> images = new ArrayList<>();
            for (int i = 0; i < n; i++) images.add("img" + n + "-" + i);
            controller.select(images);
            runBackground();
            assertTrue("count " + n, last().canAnalyze);
        }
        prepared.clear();
        assertEquals(TimetableUploadController.AnalyzeResult.STARTED, controller.analyze());
        runBackground();

        assertEquals(TimetableUploadState.Phase.UPLOADED, last().upload.phase);
        assertEquals(Arrays.asList("img5-0", "img5-1", "img5-2", "img5-3", "img5-4"), prepared);
        assertEquals(5, controller.uploadedPaths().size());
        assertNotNull(controller.takeRecognition());
        assertNull(controller.takeRecognition()); // 한 번만
    }

    @Test
    public void removingTheBadImage_reEnablesAnalyze_withoutRecheckingOthers() {
        inspectResults.put("bad", Code.UNSUPPORTED_TYPE);
        controller.select(Arrays.asList("a", "bad", "c"));
        runBackground();
        assertFalse(last().canAnalyze);
        inspected.clear();

        controller.remove("bad");
        runBackground();

        assertEquals(Arrays.asList("a", "c"), controller.selection());
        assertEquals(Arrays.asList(null, null), last().imageFailures);
        assertTrue(last().canAnalyze);
        assertTrue(inspected.isEmpty());
    }

    @Test
    public void removingAGoodImage_keepsBadImageMarked() {
        inspectResults.put("bad", Code.TOO_SMALL);
        controller.select(Arrays.asList("a", "bad"));
        runBackground();

        controller.remove("a");
        assertEquals(Arrays.asList(Code.TOO_SMALL), last().imageFailures);
        assertFalse(last().canAnalyze);
    }

    @Test
    public void selectingSix_keepsFirstFive_andReportsTruncation() {
        List<String> six = Arrays.asList("1", "2", "3", "4", "5", "6");
        assertTrue(controller.select(six));
        assertEquals(Arrays.asList("1", "2", "3", "4", "5"), controller.selection());
        assertFalse(controller.select(Arrays.asList("1", "1", "2"))); // 중복 제거 후 2장
        assertEquals(Arrays.asList("1", "2"), controller.selection());
    }

    @Test
    public void emptySelection_cannotAnalyze() {
        controller.select(Collections.emptyList());
        assertFalse(last().canAnalyze);
        assertEquals(TimetableUploadController.AnalyzeResult.IGNORED, controller.analyze());
    }

    @Test
    public void lateCheckResultFromPreviousSelection_isIgnored() {
        inspectResults.put("old", Code.CORRUPT);
        controller.select(Collections.singletonList("old"));
        Runnable staleCheck = background.poll();
        controller.select(Collections.singletonList("new"));
        staleCheck.run(); // 이전 선택의 검사 결과가 늦게 도착
        runBackground();

        assertEquals(Arrays.asList((Code) null), last().imageFailures);
        assertTrue(last().canAnalyze);
    }

    @Test
    public void inspectorCrash_isTreatedAsCorrupt() {
        TimetableUploadController<String> crashing = new TimetableUploadController<>(
                repo.repository(), recognizer.repository(), background::add, direct,
                image -> {
                    throw new IllegalStateException("decoder");
                },
                (image, limit) -> TimetableImageCompressor.Result.ok(new byte[1]), screens::add);
        crashing.select(Collections.singletonList("x"));
        runBackground();
        assertEquals(Arrays.asList(Code.CORRUPT), last().imageFailures);
        assertFalse(last().canAnalyze);
    }

    @Test
    public void failureFoundOnlyWhilePreparing_isKeptOnImage_andBlocksAnalyze() {
        // 선택 시 검사는 통과했지만 압축에서 실패(예: 5MB 미만으로 줄지 않음).
        TimetableUploadController<String> c = new TimetableUploadController<>(
                repo.repository(), recognizer.repository(), background::add, direct, image -> null,
                (image, limit) -> "b".equals(image)
                        ? TimetableImageCompressor.Result.fail(Code.COMPRESSED_TOO_LARGE)
                        : TimetableImageCompressor.Result.ok(new byte[1]),
                screens::add);
        c.select(Arrays.asList("a", "b"));
        runBackground();
        assertEquals(TimetableUploadController.AnalyzeResult.STARTED, c.analyze());
        runBackground();

        assertEquals(Arrays.asList(null, Code.COMPRESSED_TOO_LARGE), last().imageFailures);
        assertFalse(last().canAnalyze);
        assertEquals(TimetableUploadController.AnalyzeResult.IGNORED, c.analyze());
        assertTrue(repo.storedPaths().isEmpty());

        c.remove("b");
        assertTrue(last().canAnalyze);
    }

    @Test
    public void networkFailure_allowsRetryWithSameSelection() {
        controller.select(Arrays.asList("a", "b"));
        runBackground();
        repo.failNextUploadAt(1, Code.NETWORK);
        controller.analyze();
        runBackground();

        assertFalse(last().hasImageFailures());
        assertTrue(last().upload.canRetrySameSelection());
        assertTrue(last().canAnalyze);
        assertEquals(TimetableUploadController.AnalyzeResult.STARTED, controller.analyze());
        runBackground();
        assertEquals(TimetableUploadState.Phase.UPLOADED, last().upload.phase);
    }

    @Test
    public void analyzeAgainAfterReturningFromReview_doesNotUploadTwice() {
        controller.select(Arrays.asList("a", "b"));
        runBackground();
        controller.analyze();
        runBackground();
        assertNotNull(controller.takeRecognition());
        List<String> paths = new ArrayList<>(controller.uploadedPaths());
        prepared.clear();

        assertEquals(TimetableUploadController.AnalyzeResult.OPEN_REVIEW, controller.analyze());
        runBackground();

        assertTrue(prepared.isEmpty());
        assertEquals(paths, controller.uploadedPaths());
        assertEquals(paths, repo.storedPaths());
        assertNotNull(controller.takeRecognition());
    }

    @Test
    public void whileBusy_selectionChangesAndAnalyzeAreIgnored() {
        controller.select(Arrays.asList("a", "b"));
        runBackground();
        controller.analyze(); // 준비 작업이 대기열에 있음
        assertTrue(last().upload.isBusy());
        assertFalse(last().canAnalyze);

        assertEquals(TimetableUploadController.AnalyzeResult.IGNORED, controller.analyze());
        assertFalse(controller.remove("a"));
        assertEquals(Arrays.asList("a", "b"), controller.selection());
    }

    @Test
    public void newSelection_discardsUploadedImages() {
        controller.select(Arrays.asList("a", "b"));
        runBackground();
        controller.analyze();
        runBackground();
        assertEquals(2, repo.storedPaths().size());

        controller.select(Collections.singletonList("c"));
        assertTrue(repo.storedPaths().isEmpty());
        assertEquals(TimetableUploadState.Phase.IDLE, last().upload.phase);
        assertNull(controller.takeRecognition());
    }

    @Test
    public void screenSnapshot_isStableForRedrawAfterRotation() {
        // 회전 뒤 화면은 마지막 스냅샷을 다시 그린다. 같은 상태를 다시 계산해도 같아야 한다.
        inspectResults.put("big", Code.SOURCE_TOO_LARGE);
        controller.select(Arrays.asList("ok", "big"));
        runBackground();
        TimetableUploadScreen before = last();
        TimetableUploadScreen again = controller.screen();

        assertEquals(before.imageFailures, again.imageFailures);
        assertEquals(before.canAnalyze, again.canAnalyze);
        assertFalse(again.canAnalyze);
        assertNull(again.imageFailures.get(0));
    }

    // ── [BT-026] 업로드 뒤 인식 ───────────────────────────────────────────

    private void uploadTwo() {
        controller.select(Arrays.asList("a", "b"));
        runBackground();
        controller.analyze();
        runBackground();
    }

    @Test
    public void afterUpload_recognitionRunsOnce_andResultIsHandedOverOnce() {
        uploadTwo();
        assertEquals(1, recognizer.calls());
        assertFalse(last().recognizing);
        assertNull(last().recognitionFailure);
        RecognitionResult result = controller.takeRecognition();
        assertNotNull(result);
        assertEquals(4, result.courses.size()); // 서버 mock 과 같은 데이터
        assertNull(controller.takeRecognition());

        // 검수에서 돌아와 다시 분석: 업로드·인식 모두 다시 하지 않는다(AI 횟수 절약).
        assertEquals(TimetableUploadController.AnalyzeResult.OPEN_REVIEW, controller.analyze());
        assertEquals(1, recognizer.calls());
        assertEquals(result, controller.takeRecognition());
    }

    @Test
    public void retryableRecognitionFailure_allowsRetry_withoutReupload() {
        recognizer.failNext(RecognitionFailure.of(RecognitionFailure.Reason.AI_TIMEOUT));
        uploadTwo();
        assertEquals(RecognitionFailure.Reason.AI_TIMEOUT, last().recognitionFailure.getReason());
        assertTrue(last().canAnalyze);
        assertNull(controller.takeRecognition());
        List<String> paths = new ArrayList<>(controller.uploadedPaths());
        prepared.clear();

        assertEquals(TimetableUploadController.AnalyzeResult.RECOGNIZING, controller.analyze());
        runBackground();
        assertTrue(prepared.isEmpty()); // 다시 올리지 않음
        assertEquals(paths, controller.uploadedPaths());
        assertEquals(2, recognizer.calls());
        assertNull(last().recognitionFailure);
        assertNotNull(controller.takeRecognition());
    }

    @Test
    public void nonRetryableRecognitionFailure_blocksAnalyze_untilSelectionChanges() {
        recognizer.failNext(RecognitionFailure.of(RecognitionFailure.Reason.QUOTA_EXCEEDED));
        uploadTwo();
        assertEquals(RecognitionFailure.Reason.QUOTA_EXCEEDED, last().recognitionFailure.getReason());
        assertFalse(last().canAnalyze);
        assertEquals(TimetableUploadController.AnalyzeResult.IGNORED, controller.analyze());
        assertEquals(1, recognizer.calls());

        controller.remove("b");
        assertNull(last().recognitionFailure);
        assertTrue(last().canAnalyze);
    }

    @Test
    public void serverRetryableFalse_overridesReasonDefault() {
        // AI_UNAVAILABLE 은 기본 retryable 이지만 서버가 false(키·결제 설정 문제)를 주면 따른다.
        recognizer.failNext(RecognitionFailure.fromServer("AI_UNAVAILABLE", false, "req-1"));
        uploadTwo();
        assertFalse(last().canAnalyze);
    }

    @Test
    public void newSelection_dropsRecognitionResult() {
        uploadTwo();
        controller.select(Collections.singletonList("c"));
        assertNull(controller.takeRecognition());
        assertNull(last().recognitionFailure);
        assertFalse(last().recognizing);
    }

    // ── [BT-026 P4] 장수별 압축 상한 ───────────────────────────────

    @Test
    public void compressionLimit_followsSelectedCount() {
        long mib = 1024L * 1024;
        long[] expected = {0, 5 * mib, 5 * mib, 14 * mib / 3, 14 * mib / 4, 14 * mib / 5};
        for (int count = 1; count <= 5; count++) {
            List<String> pick = new ArrayList<>();
            for (int i = 0; i < count; i++) pick.add("n" + count + "-" + i);
            controller.select(pick);
            runBackground();
            limits.clear();
            controller.analyze();
            runBackground();
            assertEquals(count, limits.size());
            for (long limit : limits) {
                assertEquals("count=" + count, expected[count], limit);
                // 모든 장이 상한 미만이면 합계가 서버 상한(14MiB) 이하다.
                assertTrue(count * (limit - 1) <= TimetableImageValidator.MAX_TOTAL_UPLOAD_BYTES);
                assertTrue(limit <= TimetableImagePaths.MAX_UPLOAD_BYTES);
            }
        }
    }

    // ── [BT-026 P4] 검수가 끝나면 이미지 삭제 ────────────────────────

    @Test
    public void reviewFinishedAfterSuccess_deletesBatchOnce_andScreenExitDoesNotDeleteAgain() {
        uploadTwo();
        assertNotNull(controller.takeRecognition());
        assertEquals(2, repo.storedPaths().size());

        controller.onReviewFinished();
        assertTrue(repo.storedPaths().isEmpty());
        assertEquals(1, deletes.size());
        assertTrue(deletes.get(0).startsWith("batch:"));
        assertTrue(controller.uploadedPaths().isEmpty());

        // 다시 검수를 열어도 저장된 결과를 쓴다(업로드·인식·이미지 불필요).
        assertEquals(TimetableUploadController.AnalyzeResult.OPEN_REVIEW, controller.analyze());
        assertNotNull(controller.takeRecognition());
        assertEquals(1, recognizer.calls());

        controller.onReviewFinished(); // 두 번째 검수 종료
        controller.clear();            // 화면 종료
        assertEquals(1, deletes.size()); // deleteImages 와 겹치지 않는다
    }

    @Test
    public void reviewFinished_afterRetryableFailure_keepsImagesForRetry() {
        recognizer.failNext(RecognitionFailure.of(RecognitionFailure.Reason.AI_TIMEOUT));
        uploadTwo();
        controller.onReviewFinished();
        assertEquals(2, repo.storedPaths().size());
        assertTrue(deletes.isEmpty());

        // 다시 시도는 같은 이미지로 인식만 다시 한다.
        assertEquals(TimetableUploadController.AnalyzeResult.RECOGNIZING, controller.analyze());
        assertEquals(2, recognizer.calls());
    }

    @Test
    public void screenExitWithoutRecognition_stillDeletesWithDeleteImagesOnce() {
        recognizer.failNext(RecognitionFailure.of(RecognitionFailure.Reason.AI_NOT_IMPLEMENTED));
        uploadTwo();
        controller.clear();
        assertEquals(Collections.singletonList("images:2"), deletes);
        assertTrue(repo.storedPaths().isEmpty());
    }

    // ── [BT-026 P4] 비동기 인식(실제 Callable 처럼) ────────────────────

    private TimetableUploadController<String> asyncController(AsyncRecognizer async) {
        return new TimetableUploadController<>(images, async, background::add, direct, image -> null,
                (image, limit) -> TimetableImageCompressor.Result.ok(new byte[10]), screens::add);
    }

    @Test
    public void asyncRecognition_showsRecognizing_thenResultOnce() {
        AsyncRecognizer async = new AsyncRecognizer();
        TimetableUploadController<String> c = asyncController(async);
        c.select(Arrays.asList("a", "b"));
        runBackground();
        c.analyze();
        runBackground();
        assertTrue(last().recognizing);
        assertFalse(last().canAnalyze);
        c.onReviewFinished(); // 인식 중에는 지우지 않는다
        assertTrue(deletes.isEmpty());

        async.callbacks.get(0).onSuccess(RecognitionResultsForTest.empty(2));
        assertFalse(last().recognizing);
        assertNotNull(c.takeRecognition());
        assertNull(c.takeRecognition());
    }

    @Test
    public void asyncRecognition_lateResultAfterScreenExit_isDropped() {
        AsyncRecognizer async = new AsyncRecognizer();
        TimetableUploadController<String> c = asyncController(async);
        c.select(Arrays.asList("a", "b"));
        runBackground();
        c.analyze();
        runBackground();
        // 인식 중에는 선택을 바꿀 수 없다.
        assertFalse(c.select(Collections.singletonList("x")));
        assertEquals(Arrays.asList("a", "b"), c.selection());

        c.clear(); // 뒤로가기로 화면 종료 → 인식 결과 무시, 이미지 삭제
        assertEquals(1, async.cancels);
        assertEquals(Collections.singletonList("images:2"), deletes);
        int published = screens.size();

        async.callbacks.get(0).onSuccess(RecognitionResultsForTest.empty(2));
        async.callbacks.get(0).onError(RecognitionFailure.of(RecognitionFailure.Reason.AI_TIMEOUT));
        assertNull(c.takeRecognition());
        assertEquals(published, screens.size()); // 늦은 결과로 화면을 바꾸지 않는다
        assertEquals(1, deletes.size());
    }

    /** 테스트용 빈 인식 결과. */
    private static final class RecognitionResultsForTest {
        static RecognitionResult empty(int images) {
            return new RecognitionResult(Collections.emptyList(), Collections.emptyList(), 0, images);
        }
    }
}
