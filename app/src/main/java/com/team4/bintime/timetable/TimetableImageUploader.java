package com.team4.bintime.timetable;

import com.team4.bintime.data.repository.TimetableImageFailure;
import com.team4.bintime.data.repository.TimetableImagePaths;
import com.team4.bintime.data.repository.TimetableImageRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [BT-025] 이미지 준비(검사·압축) → 묶음 업로드 → 정리를 순서대로 진행한다. Android 화면에 의존하지 않아
 * 직접 실행 Executor와 Mock 저장소로 JVM 단위 테스트를 한다.
 * <ul>
 *   <li>{@link #start}·{@link #discard}와 저장소 콜백은 메인 스레드에서 부른다. 준비는 {@code background}에서 한다.</li>
 *   <li>모든 이미지를 먼저 검사해 문제 있는 이미지를 한 번에 보여 준다. 하나라도 문제면 업로드하지 않는다.</li>
 *   <li>시작할 때마다 세대(generation)를 올려, 선택이 바뀌거나 취소된 뒤 도착한 이전 결과는 버린다.
 *       버린 업로드가 뒤늦게 성공하면 그 이미지를 지운다.</li>
 *   <li>선택을 바꾸거나 화면을 떠나면({@link #discard}) 올린 이미지를 지운다(최선 시도).</li>
 * </ul>
 */
final class TimetableImageUploader {

    interface Preparer {
        /** index번째 선택 이미지를 업로드용 JPEG로 만든다. background 스레드에서 불린다. */
        TimetableImageCompressor.Result prepare(int index);
    }

    interface Listener {
        void onState(TimetableUploadState state);
    }

    private final TimetableImageRepository repository;
    private final Executor background;
    private final Executor main;
    private final Listener listener;
    private final AtomicInteger generation = new AtomicInteger();

    private TimetableImageRepository.Registration upload;
    private List<String> uploadedPaths = Collections.emptyList();
    /** uploadedPaths 의 묶음. 경로가 없으면 null. */
    private String uploadedBatchId;
    private TimetableUploadState state = TimetableUploadState.idle();

    TimetableImageUploader(TimetableImageRepository repository, Executor background, Executor main,
                           Listener listener) {
        if (repository == null || background == null || main == null || listener == null) {
            throw new IllegalArgumentException("null argument");
        }
        this.repository = repository;
        this.background = background;
        this.main = main;
        this.listener = listener;
    }

    TimetableUploadState getState() {
        return state;
    }

    /** UPLOADED일 때 index 순서 경로(analyzeTimetable imagePaths). 그 외에는 빈 목록. */
    List<String> getUploadedPaths() {
        return uploadedPaths;
    }

    /** count장 준비·업로드를 시작한다. 진행 중이면 무시한다(중복 클릭 방지). */
    void start(int count, Preparer preparer) {
        if (state.isBusy()) return;
        cancelAndDeleteUploaded();
        int gen = generation.incrementAndGet();
        if (TimetableImageValidator.checkCount(count) != null || preparer == null) {
            emit(TimetableUploadState.failed(Math.max(0, count),
                    TimetableImageFailure.of(TimetableImageFailure.Code.INVALID_COUNT, "count")));
            return;
        }
        String batchId = TimetableImagePaths.newBatchId();
        emit(TimetableUploadState.preparing(count));
        background.execute(() -> {
            List<byte[]> jpegs = new ArrayList<>();
            List<TimetableImageFailure.Code> failures = new ArrayList<>();
            boolean anyFailed = false;
            for (int i = 0; i < count; i++) {
                if (gen != generation.get()) return;
                TimetableImageCompressor.Result result;
                try {
                    result = preparer.prepare(i);
                } catch (RuntimeException e) {
                    result = TimetableImageCompressor.Result.fail(TimetableImageFailure.Code.CORRUPT);
                }
                TimetableImageFailure.Code failure = result == null
                        ? TimetableImageFailure.Code.CORRUPT : result.failure;
                if (failure == null) failure = TimetableImageValidator.checkUploadSize(result.jpeg);
                failures.add(failure);
                jpegs.add(failure == null ? result.jpeg : null);
                anyFailed |= failure != null;
            }
            boolean invalid = anyFailed;
            main.execute(() -> {
                if (gen != generation.get()) return;
                if (invalid) {
                    emit(TimetableUploadState.invalidImages(failures));
                } else {
                    upload(gen, batchId, jpegs);
                }
            });
        });
    }

    /**
     * [BT-026] 올린 묶음을 {@code deleteBatch}로 지우고 경로를 잊는다(상태는 그대로). 인식이 성공해 이미지가 더 필요
     * 없을 때 부른다. 실패는 저장소가 코드만 로그로 남기고(콜백 없음) 사용자에게 알리지 않는다. 남은 파일은 버킷
     * Lifecycle(1일)이 정리한다. 경로를 잊으므로 뒤의 {@link #discard}가 같은 이미지를 다시 지우지 않는다.
     */
    void deleteUploadedBatch() {
        if (uploadedPaths.isEmpty() || uploadedBatchId == null) return;
        String batchId = uploadedBatchId;
        uploadedPaths = Collections.emptyList();
        uploadedBatchId = null;
        repository.deleteBatch(batchId, null);
    }

    /** 진행 중인 작업을 취소하고 올린 이미지를 지운 뒤 IDLE로 돌아간다. 선택 변경·화면 종료 시 부른다. */
    void discard() {
        generation.incrementAndGet();
        cancelAndDeleteUploaded();
        emit(TimetableUploadState.idle());
    }

    private void upload(int gen, String batchId, List<byte[]> jpegs) {
        int count = jpegs.size();
        long[] sent = new long[count];
        long total = 0;
        for (byte[] jpeg : jpegs) total += jpeg.length;
        long totalBytes = Math.max(1, total);
        emit(TimetableUploadState.uploading(count, 0));
        TimetableImageRepository.Registration registration = repository.uploadBatch(batchId, jpegs,
                new TimetableImageRepository.UploadListener() {
                    @Override
                    public void onProgress(int index, long sentBytes, long totalBytesOfImage) {
                        if (gen != generation.get() || index < 0 || index >= count) return;
                        sent[index] = Math.max(0, Math.min(sentBytes, jpegs.get(index).length));
                        long sum = 0;
                        for (long s : sent) sum += s;
                        int percent = (int) (sum * 100 / totalBytes);
                        if (percent != state.progressPercent) {
                            emit(TimetableUploadState.uploading(count, percent));
                        }
                    }

                    @Override
                    public void onSuccess(List<String> paths) {
                        if (gen != generation.get()) {
                            // 취소·선택 변경 뒤 도착한 성공: 남기지 않는다.
                            repository.deleteImages(paths, null);
                            return;
                        }
                        upload = null;
                        uploadedPaths = paths == null ? Collections.emptyList()
                                : Collections.unmodifiableList(new ArrayList<>(paths));
                        uploadedBatchId = uploadedPaths.isEmpty() ? null : batchId;
                        emit(TimetableUploadState.uploaded(uploadedPaths));
                    }

                    @Override
                    public void onError(TimetableImageFailure failure) {
                        if (gen != generation.get()) return;
                        upload = null;
                        emit(TimetableUploadState.failed(count, failure));
                    }
                });
        // Mock은 콜백을 즉시 부르므로 아직 업로드 중일 때만 취소 핸들을 보관한다.
        if (gen == generation.get() && state.phase == TimetableUploadState.Phase.UPLOADING) {
            upload = registration;
        }
    }

    private void cancelAndDeleteUploaded() {
        if (upload != null) {
            upload.cancel();
            upload = null;
        }
        if (!uploadedPaths.isEmpty()) {
            repository.deleteImages(uploadedPaths, null);
            uploadedPaths = Collections.emptyList();
            uploadedBatchId = null;
        }
    }

    private void emit(TimetableUploadState next) {
        state = next;
        listener.onState(next);
    }
}
