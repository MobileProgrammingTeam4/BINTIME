package com.team4.bintime.timetable;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.team4.bintime.data.recognition.RecognitionResult;
import com.team4.bintime.data.recognition.TimetableRecognitionRepository;
import com.team4.bintime.data.repository.TimetableImageRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [BT-025/026] 업로드 화면의 선택·검사·업로드·인식 상태를 회전 뒤에도 유지한다. 판정과 흐름은
 * {@link TimetableUploadController}가 하고, 이 클래스는 Android 연결(스레드, Uri 검사·압축)만 맡는다.
 * 화면을 완전히 떠나면({@link #onCleared}) 진행 중인 업로드를 취소하고 올린 이미지를 지운다.
 * 메인 스레드에서만 호출한다.
 */
public class TimetableUploadViewModel extends ViewModel {

    private final MutableLiveData<TimetableUploadScreen> screen =
            new MutableLiveData<>(TimetableUploadScreen.empty());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TimetableUploadController<Uri> controller;

    LiveData<TimetableUploadScreen> getScreen() {
        return screen;
    }

    /**
     * 처음 한 번 저장소와 resolver를 연결한다. 회전 뒤 다시 불러도 기존 연결을 유지한다.
     * resolver는 Application의 것을 넘긴다(화면 Context를 붙잡지 않도록).
     */
    void attach(TimetableImageRepository repository, TimetableRecognitionRepository recognizer,
                ContentResolver resolver) {
        if (controller != null) return;
        controller = new TimetableUploadController<>(repository, recognizer, background, mainHandler::post,
                uri -> TimetableImageCompressor.inspect(resolver, uri),
                (uri, limit) -> TimetableImageCompressor.prepare(resolver, uri, limit),
                screen::setValue);
    }

    List<Uri> selection() {
        return controller == null ? new ArrayList<>() : controller.selection();
    }

    /** @return 최대 장수를 넘어 잘랐으면 true */
    boolean select(List<Uri> picked) {
        return controller.select(picked);
    }

    /** 프로세스 종료 뒤 복원: 이미 선택이 있으면(회전) 그대로 둔다. */
    void restoreIfEmpty(List<Uri> saved) {
        if (saved != null && !saved.isEmpty() && controller.selection().isEmpty()) controller.select(saved);
    }

    void remove(Uri uri) {
        controller.remove(uri);
    }

    /** 분석 시작 요청. 분석 가능 판정을 통과하지 못하면 무시된다. */
    TimetableUploadController.AnalyzeResult analyze() {
        return controller.analyze();
    }

    /** 검수로 넘어갈 차례면 인식 결과를 한 번만 돌려준다. 아니면 null. */
    RecognitionResult takeRecognition() {
        return controller == null ? null : controller.takeRecognition();
    }

    /** 검수 화면이 끝나면(저장·뒤로) 인식에 쓴 이미지를 지운다. */
    void onReviewFinished() {
        if (controller != null) controller.onReviewFinished();
    }

    /** analyzeTimetable 에 넘긴 경로. UPLOADED가 아니면 빈 목록. */
    List<String> uploadedPaths() {
        return controller == null ? new ArrayList<>() : controller.uploadedPaths();
    }

    @Override
    protected void onCleared() {
        if (controller != null) controller.clear();
        background.shutdownNow();
        super.onCleared();
    }
}
