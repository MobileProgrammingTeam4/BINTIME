package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.repository.MockTimetableImageRepositoryAccess;
import com.team4.bintime.data.repository.TimetableImageFailure.Code;

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
    /** 선택 시 검사 결과. 없으면 통과. */
    private final Map<String, Code> inspectResults = new HashMap<>();
    private final List<String> inspected = new ArrayList<>();
    private final List<String> prepared = new ArrayList<>();
    private final List<TimetableUploadScreen> screens = new ArrayList<>();

    private final TimetableUploadController<String> controller = new TimetableUploadController<>(
            repo.repository(), background::add, direct,
            image -> {
                inspected.add(image);
                return inspectResults.get(image);
            },
            image -> {
                prepared.add(image);
                return TimetableImageCompressor.Result.ok(new byte[100]);
            },
            screens::add);

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
        assertFalse(controller.takeRecognitionRequest());
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
        assertTrue(controller.takeRecognitionRequest());
        assertFalse(controller.takeRecognitionRequest()); // 한 번만
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
                repo.repository(), background::add, direct,
                image -> {
                    throw new IllegalStateException("decoder");
                },
                image -> TimetableImageCompressor.Result.ok(new byte[1]), screens::add);
        crashing.select(Collections.singletonList("x"));
        runBackground();
        assertEquals(Arrays.asList(Code.CORRUPT), last().imageFailures);
        assertFalse(last().canAnalyze);
    }

    @Test
    public void failureFoundOnlyWhilePreparing_isKeptOnImage_andBlocksAnalyze() {
        // 선택 시 검사는 통과했지만 압축에서 실패(예: 5MB 미만으로 줄지 않음).
        TimetableUploadController<String> c = new TimetableUploadController<>(
                repo.repository(), background::add, direct, image -> null,
                image -> "b".equals(image)
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
        assertTrue(controller.takeRecognitionRequest());
        List<String> paths = new ArrayList<>(controller.uploadedPaths());
        prepared.clear();

        assertEquals(TimetableUploadController.AnalyzeResult.OPEN_REVIEW, controller.analyze());
        runBackground();

        assertTrue(prepared.isEmpty());
        assertEquals(paths, controller.uploadedPaths());
        assertEquals(paths, repo.storedPaths());
        assertTrue(controller.takeRecognitionRequest());
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
        assertFalse(controller.takeRecognitionRequest());
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
}
