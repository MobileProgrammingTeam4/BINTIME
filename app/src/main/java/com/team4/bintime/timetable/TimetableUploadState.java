package com.team4.bintime.timetable;

import com.team4.bintime.data.repository.TimetableImageFailure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [BT-025] 업로드 화면 상태 스냅샷(불변). {@link TimetableImageUploader}가 만들고 화면은 그대로 그린다.
 * 이미지 내용·URI는 담지 않는다.
 */
final class TimetableUploadState {

    enum Phase {
        /** 아직 시작하지 않았거나 선택이 바뀌어 초기화됐다. */
        IDLE,
        /** 이미지 검사·압축 중. */
        PREPARING,
        /** Storage 업로드 중. */
        UPLOADING,
        /** 모든 이미지 업로드 완료. {@link #paths} 사용 가능. */
        UPLOADED,
        /** 실패. 이미지별 실패는 {@link #imageFailures}, 전체 실패는 {@link #failure}. */
        FAILED
    }

    final Phase phase;
    final int imageCount;
    /** 업로드 진행률 0..100. */
    final int progressPercent;
    /** index별 실패 코드(없으면 null). 길이 = imageCount. */
    final List<TimetableImageFailure.Code> imageFailures;
    /** 이미지와 무관한 실패 또는 업로드 단계 실패. */
    final TimetableImageFailure failure;
    /** UPLOADED일 때 index 순서 경로. 그 외에는 빈 목록. */
    final List<String> paths;

    private TimetableUploadState(Phase phase, int imageCount, int progressPercent,
                                 List<TimetableImageFailure.Code> imageFailures,
                                 TimetableImageFailure failure, List<String> paths) {
        this.phase = phase;
        this.imageCount = imageCount;
        this.progressPercent = Math.max(0, Math.min(100, progressPercent));
        List<TimetableImageFailure.Code> failures = new ArrayList<>(imageFailures == null
                ? Collections.nCopies(imageCount, (TimetableImageFailure.Code) null) : imageFailures);
        this.imageFailures = Collections.unmodifiableList(failures);
        this.failure = failure;
        this.paths = paths == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(paths));
    }

    static TimetableUploadState idle() {
        return new TimetableUploadState(Phase.IDLE, 0, 0, null, null, null);
    }

    static TimetableUploadState preparing(int count) {
        return new TimetableUploadState(Phase.PREPARING, count, 0, null, null, null);
    }

    static TimetableUploadState uploading(int count, int percent) {
        return new TimetableUploadState(Phase.UPLOADING, count, percent, null, null, null);
    }

    static TimetableUploadState uploaded(List<String> paths) {
        return new TimetableUploadState(Phase.UPLOADED, paths.size(), 100, null, null, paths);
    }

    /** 이미지별 검사 실패(업로드 전). */
    static TimetableUploadState invalidImages(List<TimetableImageFailure.Code> failures) {
        return new TimetableUploadState(Phase.FAILED, failures.size(), 0, failures, null, null);
    }

    /**
     * 업로드 단계 실패. 이미지 자체 문제({@link #isImageProblem})이고 index가 있으면 그 이미지에도 표시한다.
     * 네트워크·권한 실패는 이미지 탓이 아니므로 전체 실패로만 둔다.
     */
    static TimetableUploadState failed(int count, TimetableImageFailure failure) {
        List<TimetableImageFailure.Code> failures = new ArrayList<>(
                Collections.nCopies(count, (TimetableImageFailure.Code) null));
        if (failure != null && isImageProblem(failure.getCode())
                && failure.getIndex() >= 0 && failure.getIndex() < count) {
            failures.set(failure.getIndex(), failure.getCode());
        }
        return new TimetableUploadState(Phase.FAILED, count, 0, failures, failure, null);
    }

    /** 다른 이미지를 골라야 해결되는 실패인지. */
    static boolean isImageProblem(TimetableImageFailure.Code code) {
        if (code == null) return false;
        switch (code) {
            case UNSUPPORTED_TYPE:
            case SOURCE_TOO_LARGE:
            case CORRUPT:
            case TOO_SMALL:
            case COMPRESSED_TOO_LARGE:
            case READ_FAILED:
                return true;
            default:
                return false;
        }
    }

    boolean isBusy() {
        return phase == Phase.PREPARING || phase == Phase.UPLOADING;
    }

    boolean hasImageFailures() {
        for (TimetableImageFailure.Code code : imageFailures) if (code != null) return true;
        return false;
    }

    /** 같은 선택 그대로 "다시 시도"할 수 있는 실패인지(이미지 자체 문제가 아닌 경우). */
    boolean canRetrySameSelection() {
        return phase == Phase.FAILED && !hasImageFailures() && failure != null && failure.isRetryable();
    }
}
