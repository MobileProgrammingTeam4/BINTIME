package com.team4.bintime.timetable;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.team4.bintime.data.repository.TimetableImageRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [BT-025] 업로드 화면 회전 뒤에도 준비·업로드를 이어 가기 위한 ViewModel.
 * 화면을 완전히 떠나면({@link #onCleared}) 진행 중인 업로드를 취소하고 올린 이미지를 지운다.
 * 메인 스레드에서만 호출한다.
 */
public class TimetableUploadViewModel extends ViewModel {

    private final MutableLiveData<TimetableUploadState> state =
            new MutableLiveData<>(TimetableUploadState.idle());
    private final ExecutorService preparation = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TimetableImageUploader uploader;
    /** 업로드가 끝나 다음 단계(인식)로 넘어가야 한다. 화면이 한 번만 소비한다. */
    private boolean recognitionPending;

    LiveData<TimetableUploadState> getState() {
        return state;
    }

    /** 처음 한 번 저장소를 연결한다. 회전 뒤 다시 불러도 기존 연결을 유지한다. */
    void attach(TimetableImageRepository repository) {
        if (uploader != null) return;
        uploader = new TimetableImageUploader(repository, preparation, mainHandler::post, next -> {
            if (next.phase == TimetableUploadState.Phase.UPLOADED) recognitionPending = true;
            state.setValue(next);
        });
    }

    /** 선택한 이미지를 준비·업로드한다. 진행 중이면 무시한다. resolver는 Application의 것을 넘긴다. */
    void start(ContentResolver resolver, List<Uri> images) {
        List<Uri> selection = new ArrayList<>(images);
        uploader.start(selection.size(), index -> TimetableImageCompressor.prepare(resolver, selection.get(index)));
    }

    /** 선택이 바뀌었다: 진행 중인 작업 취소, 올린 이미지 삭제. */
    void discard() {
        recognitionPending = false;
        if (uploader != null) uploader.discard();
    }

    /** 이미 올린 같은 선택으로 다시 인식 단계로 넘어간다(검수 화면에서 뒤로 온 뒤 다시 분석). */
    void requestRecognitionAgain() {
        TimetableUploadState current = state.getValue();
        if (current != null && current.phase == TimetableUploadState.Phase.UPLOADED) recognitionPending = true;
    }

    /** 인식으로 넘어갈 차례면 true를 한 번만 돌려준다. */
    boolean takeRecognitionRequest() {
        boolean pending = recognitionPending;
        recognitionPending = false;
        return pending;
    }

    /** analyzeTimetable 에 넘길 경로(P3). UPLOADED가 아니면 빈 목록. */
    List<String> uploadedPaths() {
        return uploader == null ? new ArrayList<>() : uploader.getUploadedPaths();
    }

    @Override
    protected void onCleared() {
        if (uploader != null) uploader.discard();
        preparation.shutdownNow();
        super.onCleared();
    }
}
