package com.team4.bintime.timetable;

import com.team4.bintime.data.recognition.RecognitionFailure;
import com.team4.bintime.data.recognition.RecognitionResult;
import com.team4.bintime.data.recognition.TimetableRecognitionRepository;
import com.team4.bintime.data.repository.TimetableImageFailure;
import com.team4.bintime.data.repository.TimetableImagePaths;
import com.team4.bintime.data.repository.TimetableImageRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * [BT-025/026] 업로드 화면의 단일 진입점. 선택 목록, 선택 시 검사, "분석 가능" 판정, 업로드 시작, 인식(analyzeTimetable)
 * 호출과 검수 이동 요청을 한곳에서 관리한다. Android에 의존하지 않아 JVM 단위 테스트로 검증한다({@code K}는 화면에서 Uri).
 * <ul>
 *   <li>이미지를 고르면 바로 백그라운드에서 원본을 검사({@link Inspector}: 형식 → 크기 → 헤더 해상도, 디코딩 없음)해
 *       문제 있는 이미지를 표시한다. 검사가 끝나기 전에는 분석할 수 없다.</li>
 *   <li>{@link #analyze()}는 {@link #canAnalyze()}를 통과해야만 압축·업로드를 시작한다. 화면 버튼 상태와 무관하게
 *       여기서 다시 막는다.</li>
 *   <li>준비 단계에서 새로 찾은 이미지 오류도 그 이미지에 남겨, 다른 이미지를 빼도 계속 막는다.</li>
 *   <li>[BT-026] 업로드가 끝나면 바로 인식을 부른다. 성공하면 결과를 한 번 넘겨 검수로 간다({@link #takeRecognition}).
 *       같은 선택으로 다시 분석하면 업로드·인식을 다시 하지 않고 같은 결과로 검수를 연다(AI 사용 횟수 절약).
 *       실패하면 서버 retryable 대로 "다시 시도"(이미 올린 이미지로 인식만 다시)를 허용하고, 아니면 선택을 바꿀 때까지 막는다.
 *       결과는 검수용이며 여기서 저장소에 쓰지 않는다.</li>
 * </ul>
 * 모든 공개 메서드와 콜백은 main 스레드에서 부른다. 검사·준비는 {@code background}에서 한다.
 */
final class TimetableUploadController<K> {

    /** 선택 시 원본 검사. 통과하면 null. background 스레드에서 불린다. */
    interface Inspector<K> {
        TimetableImageFailure.Code inspect(K image);
    }

    /**
     * 업로드용 JPEG 준비(검사 + 압축). background 스레드에서 불린다. limitExclusive 는 장수로 정한 한 장 상한
     * ({@link TimetableImageValidator#uploadLimit}).
     */
    interface Preparer<K> {
        TimetableImageCompressor.Result prepare(K image, long limitExclusive);
    }

    interface Listener {
        void onScreen(TimetableUploadScreen screen);
    }

    enum AnalyzeResult {
        /** 분석할 수 없는 상태라 아무 일도 하지 않았다. */
        IGNORED,
        /** 준비·업로드를 시작했다. */
        STARTED,
        /** 이미 올린 이미지로 인식만 다시 시작했다. */
        RECOGNIZING,
        /** 같은 선택을 이미 올렸다. 다시 올리지 않고 인식(검수)으로 넘어간다. */
        OPEN_REVIEW
    }

    private static final class Item<K> {
        final K image;
        boolean checked;
        TimetableImageFailure.Code failure;

        Item(K image) {
            this.image = image;
        }
    }

    private final Executor background;
    private final Executor main;
    private final Inspector<K> inspector;
    private final Preparer<K> preparer;
    private final Listener listener;
    private final TimetableImageUploader uploader;
    private final TimetableRecognitionRepository recognizer;

    private final List<Item<K>> items = new ArrayList<>();
    /** select()마다 올린다. 이전 선택의 검사 결과가 늦게 도착하면 버린다. */
    private int selectionGeneration;
    /** 업로드 중인(또는 마지막으로 올린) 선택. 준비 단계 오류를 이미지에 옮길 때 쓴다. */
    private List<K> inFlight = Collections.emptyList();
    private TimetableUploadState upload = TimetableUploadState.idle();
    /** 검수로 넘어갈 차례. {@link #takeRecognition}이 한 번 소비한다. */
    private boolean recognitionPending;
    private boolean recognizing;
    private RecognitionResult recognition;
    private RecognitionFailure recognitionFailure;
    private TimetableRecognitionRepository.Registration recognitionCall;
    /** 인식을 시작할 때마다 올린다. 취소·선택 변경 뒤 도착한 결과는 버린다. */
    private int recognitionGeneration;

    TimetableUploadController(TimetableImageRepository repository, TimetableRecognitionRepository recognizer,
                              Executor background, Executor main, Inspector<K> inspector, Preparer<K> preparer,
                              Listener listener) {
        this(repository, recognizer, background, main, inspector, preparer, listener,
                TimetableImageUploader.LocalCopy.NONE);
    }

    /** [BT-048] localCopy: 검수 화면 '원본 보기'용 기기 안 사본(앱 캐시). */
    TimetableUploadController(TimetableImageRepository repository, TimetableRecognitionRepository recognizer,
                              Executor background, Executor main, Inspector<K> inspector, Preparer<K> preparer,
                              Listener listener, TimetableImageUploader.LocalCopy localCopy) {
        if (repository == null || recognizer == null || background == null || main == null || inspector == null
                || preparer == null || listener == null || localCopy == null) {
            throw new IllegalArgumentException("null argument");
        }
        this.recognizer = recognizer;
        this.background = background;
        this.main = main;
        this.inspector = inspector;
        this.preparer = preparer;
        this.listener = listener;
        this.uploader = new TimetableImageUploader(repository, background, main, this::onUploadState, localCopy);
    }

    /** 현재 선택(순서 = 업로드 index). */
    List<K> selection() {
        List<K> images = new ArrayList<>(items.size());
        for (Item<K> item : items) images.add(item.image);
        return images;
    }

    /**
     * 새 선택으로 바꾼다. 중복은 빼고 앞의 {@link TimetableImagePaths#MAX_IMAGES}장만 담는다. 진행 중인 업로드를
     * 취소하고 올린 이미지를 지운다. 업로드 중이면 무시한다.
     *
     * @return 최대 장수를 넘어 잘랐으면 true(화면 안내용)
     */
    boolean select(List<K> picked) {
        if (isBusy()) return false;
        List<K> unique = new ArrayList<>(new LinkedHashSet<>(picked == null ? Collections.<K>emptyList() : picked));
        unique.remove(null);
        boolean truncated = unique.size() > TimetableImagePaths.MAX_IMAGES;
        discardUpload();
        items.clear();
        int gen = ++selectionGeneration;
        for (K image : unique.subList(0, Math.min(unique.size(), TimetableImagePaths.MAX_IMAGES))) {
            Item<K> item = new Item<>(image);
            items.add(item);
            background.execute(() -> {
                TimetableImageFailure.Code code;
                try {
                    code = inspector.inspect(image);
                } catch (RuntimeException e) {
                    code = TimetableImageFailure.Code.CORRUPT;
                }
                TimetableImageFailure.Code result = code;
                main.execute(() -> onInspected(gen, item, result));
            });
        }
        publish();
        return truncated;
    }

    /** 이미지 하나를 뺀다. 다른 이미지의 검사 결과는 유지한다. 업로드 중이면 무시하고 false. */
    boolean remove(K image) {
        if (isBusy()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).image.equals(image)) {
                discardUpload();
                items.remove(i);
                publish();
                return true;
            }
        }
        return false;
    }

    /**
     * 분석 가능: 업로드·인식 중이 아님 + 1~5장 + 모든 이미지 검사 완료 + 오류 이미지 없음
     * + 다시 시도할 수 없는 인식 실패(예: 하루 횟수 초과)가 남아 있지 않음.
     */
    boolean canAnalyze() {
        if (isBusy() || TimetableImageValidator.checkCount(items.size()) != null) return false;
        if (recognitionFailure != null && !recognitionFailure.isRetryable()) return false;
        for (Item<K> item : items) {
            if (!item.checked || item.failure != null) return false;
        }
        return !upload.hasImageFailures();
    }

    /** 분석 시작 요청. {@link #canAnalyze()}가 false면 아무것도 압축기·업로더로 넘기지 않는다. */
    AnalyzeResult analyze() {
        if (!canAnalyze()) return AnalyzeResult.IGNORED;
        if (upload.phase == TimetableUploadState.Phase.UPLOADED && selection().equals(inFlight)) {
            if (recognition != null) {
                recognitionPending = true;
                publish();
                return AnalyzeResult.OPEN_REVIEW;
            }
            startRecognition();
            return AnalyzeResult.RECOGNIZING;
        }
        List<K> frozen = Collections.unmodifiableList(selection());
        inFlight = frozen;
        // 장수는 여기서 확정된다(업로드 중에는 선택을 바꿀 수 없다). 합계가 서버 상한을 넘지 않게 한 장 상한을 정한다.
        long limit = TimetableImageValidator.uploadLimit(frozen.size());
        uploader.start(frozen.size(), index -> preparer.prepare(frozen.get(index), limit));
        return AnalyzeResult.STARTED;
    }

    /** 검수로 넘어갈 차례면 인식 결과를 한 번만 돌려준다. 아니면 null. */
    RecognitionResult takeRecognition() {
        if (!recognitionPending || recognition == null) return null;
        recognitionPending = false;
        return recognition;
    }

    /**
     * [BT-026] 검수가 끝났다(저장했거나 검수 화면을 떠났다). 인식에 성공한 묶음이면 이미지를 지운다
     * ({@code deleteBatch}). 다시 검수를 열어도 저장해 둔 인식 결과를 쓰므로 이미지는 더 필요 없다.
     * 인식 실패(다시 시도 대기)·인식 전에는 지우지 않는다. 이미 지웠으면 아무것도 하지 않는다.
     */
    void onReviewFinished() {
        if (recognition == null || recognizing) return;
        uploader.deleteUploadedBatch();
    }

    /** [BT-048] 검수 화면 '원본 보기'용 기기 안 사본 경로(업로드 index 순서). 지웠거나 없으면 빈 목록. */
    List<String> localImagePaths() {
        return uploader.getLocalPaths();
    }

    /** analyzeTimetable 에 넘긴(넘길) 경로. UPLOADED가 아니면 빈 목록. */
    List<String> uploadedPaths() {
        return uploader.getUploadedPaths();
    }

    /** 선택을 비우고 올린 이미지를 지운다(화면 종료). */
    void clear() {
        selectionGeneration++;
        items.clear();
        discardUpload();
    }

    TimetableUploadScreen screen() {
        List<TimetableImageFailure.Code> failures = new ArrayList<>(items.size());
        boolean checking = false;
        for (Item<K> item : items) {
            failures.add(item.failure);
            checking |= !item.checked;
        }
        return new TimetableUploadScreen(failures, checking, upload, recognizing, recognitionFailure, canAnalyze());
    }

    private boolean isBusy() {
        return upload.isBusy() || recognizing;
    }

    private void startRecognition() {
        List<String> paths = uploader.getUploadedPaths();
        if (paths.isEmpty()) {
            recognitionFailure = RecognitionFailure.of(RecognitionFailure.Reason.INTERNAL);
            publish();
            return;
        }
        int gen = ++recognitionGeneration;
        recognizing = true;
        recognitionFailure = null;
        recognition = null;
        publish();
        TimetableRecognitionRepository.Registration call = recognizer.analyze(new ArrayList<>(paths),
                new TimetableRecognitionRepository.Callback() {
                    @Override
                    public void onSuccess(RecognitionResult result) {
                        if (gen != recognitionGeneration) return;
                        recognizing = false;
                        recognitionCall = null;
                        recognition = result;
                        recognitionPending = true;
                        publish();
                    }

                    @Override
                    public void onError(RecognitionFailure failure) {
                        if (gen != recognitionGeneration) return;
                        recognizing = false;
                        recognitionCall = null;
                        recognitionFailure = failure == null
                                ? RecognitionFailure.of(RecognitionFailure.Reason.INTERNAL) : failure;
                        publish();
                    }
                });
        // Mock 은 콜백을 즉시 부르므로 아직 인식 중일 때만 취소 핸들을 보관한다.
        if (gen == recognitionGeneration && recognizing) recognitionCall = call;
    }

    private void cancelRecognition() {
        recognitionGeneration++;
        if (recognitionCall != null) {
            recognitionCall.cancel();
            recognitionCall = null;
        }
        recognizing = false;
        recognition = null;
        recognitionFailure = null;
        recognitionPending = false;
    }

    private void onInspected(int gen, Item<K> item, TimetableImageFailure.Code code) {
        if (gen != selectionGeneration || !items.contains(item)) return;
        item.checked = true;
        if (item.failure == null) item.failure = code;
        publish();
    }

    private void onUploadState(TimetableUploadState next) {
        upload = next;
        if (next.phase == TimetableUploadState.Phase.UPLOADED) {
            startRecognition();
            return; // startRecognition 이 화면을 갱신한다
        }
        if (next.phase == TimetableUploadState.Phase.FAILED && selection().equals(inFlight)) {
            // 준비·업로드 중 찾은 이미지 자체 문제를 그 이미지에 남긴다.
            for (int i = 0; i < next.imageFailures.size() && i < items.size(); i++) {
                TimetableImageFailure.Code code = next.imageFailures.get(i);
                if (code != null) items.get(i).failure = code;
            }
        }
        publish();
    }

    private void discardUpload() {
        cancelRecognition();
        inFlight = Collections.emptyList();
        uploader.discard();
    }

    private void publish() {
        listener.onScreen(screen());
    }
}
