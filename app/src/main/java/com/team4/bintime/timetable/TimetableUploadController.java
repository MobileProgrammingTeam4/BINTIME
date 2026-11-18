package com.team4.bintime.timetable;

import com.team4.bintime.data.repository.TimetableImageFailure;
import com.team4.bintime.data.repository.TimetableImagePaths;
import com.team4.bintime.data.repository.TimetableImageRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * [BT-025] 업로드 화면의 단일 진입점. 선택 목록, 선택 시 검사, "분석 가능" 판정, 업로드 시작, 인식 이동 요청을
 * 한곳에서 관리한다. Android에 의존하지 않아 JVM 단위 테스트로 검증한다({@code K}는 화면에서 Uri).
 * <ul>
 *   <li>이미지를 고르면 바로 백그라운드에서 원본을 검사({@link Inspector}: 형식 → 크기 → 헤더 해상도, 디코딩 없음)해
 *       문제 있는 이미지를 표시한다. 검사가 끝나기 전에는 분석할 수 없다.</li>
 *   <li>{@link #analyze()}는 {@link #canAnalyze()}를 통과해야만 압축·업로드를 시작한다. 화면 버튼 상태와 무관하게
 *       여기서 다시 막는다.</li>
 *   <li>준비 단계에서 새로 찾은 이미지 오류도 그 이미지에 남겨, 다른 이미지를 빼도 계속 막는다.</li>
 * </ul>
 * 모든 공개 메서드와 콜백은 main 스레드에서 부른다. 검사·준비는 {@code background}에서 한다.
 */
final class TimetableUploadController<K> {

    /** 선택 시 원본 검사. 통과하면 null. background 스레드에서 불린다. */
    interface Inspector<K> {
        TimetableImageFailure.Code inspect(K image);
    }

    /** 업로드용 JPEG 준비(검사 + 압축). background 스레드에서 불린다. */
    interface Preparer<K> {
        TimetableImageCompressor.Result prepare(K image);
    }

    interface Listener {
        void onScreen(TimetableUploadScreen screen);
    }

    enum AnalyzeResult {
        /** 분석할 수 없는 상태라 아무 일도 하지 않았다. */
        IGNORED,
        /** 준비·업로드를 시작했다. */
        STARTED,
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

    private final List<Item<K>> items = new ArrayList<>();
    /** select()마다 올린다. 이전 선택의 검사 결과가 늦게 도착하면 버린다. */
    private int selectionGeneration;
    /** 업로드 중인(또는 마지막으로 올린) 선택. 준비 단계 오류를 이미지에 옮길 때 쓴다. */
    private List<K> inFlight = Collections.emptyList();
    private TimetableUploadState upload = TimetableUploadState.idle();
    private boolean recognitionPending;

    TimetableUploadController(TimetableImageRepository repository, Executor background, Executor main,
                              Inspector<K> inspector, Preparer<K> preparer, Listener listener) {
        if (repository == null || background == null || main == null || inspector == null
                || preparer == null || listener == null) {
            throw new IllegalArgumentException("null argument");
        }
        this.background = background;
        this.main = main;
        this.inspector = inspector;
        this.preparer = preparer;
        this.listener = listener;
        this.uploader = new TimetableImageUploader(repository, background, main, this::onUploadState);
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
        if (upload.isBusy()) return false;
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
        if (upload.isBusy()) return false;
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

    /** 분석 가능: 업로드 중이 아님 + 1~5장 + 모든 이미지 검사 완료 + 오류 이미지 없음. */
    boolean canAnalyze() {
        if (upload.isBusy() || TimetableImageValidator.checkCount(items.size()) != null) return false;
        for (Item<K> item : items) {
            if (!item.checked || item.failure != null) return false;
        }
        return !upload.hasImageFailures();
    }

    /** 분석 시작 요청. {@link #canAnalyze()}가 false면 아무것도 압축기·업로더로 넘기지 않는다. */
    AnalyzeResult analyze() {
        if (!canAnalyze()) return AnalyzeResult.IGNORED;
        if (upload.phase == TimetableUploadState.Phase.UPLOADED && selection().equals(inFlight)) {
            recognitionPending = true;
            publish();
            return AnalyzeResult.OPEN_REVIEW;
        }
        List<K> frozen = Collections.unmodifiableList(selection());
        inFlight = frozen;
        uploader.start(frozen.size(), index -> preparer.prepare(frozen.get(index)));
        return AnalyzeResult.STARTED;
    }

    /** 인식(검수)으로 넘어갈 차례면 true를 한 번만 돌려준다. */
    boolean takeRecognitionRequest() {
        boolean pending = recognitionPending;
        recognitionPending = false;
        return pending;
    }

    /** analyzeTimetable 에 넘길 경로(P3). UPLOADED가 아니면 빈 목록. */
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
        return new TimetableUploadScreen(failures, checking, upload, canAnalyze());
    }

    private void onInspected(int gen, Item<K> item, TimetableImageFailure.Code code) {
        if (gen != selectionGeneration || !items.contains(item)) return;
        item.checked = true;
        if (item.failure == null) item.failure = code;
        publish();
    }

    private void onUploadState(TimetableUploadState next) {
        upload = next;
        if (next.phase == TimetableUploadState.Phase.UPLOADED) recognitionPending = true;
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
        recognitionPending = false;
        inFlight = Collections.emptyList();
        uploader.discard();
    }

    private void publish() {
        listener.onScreen(screen());
    }
}
