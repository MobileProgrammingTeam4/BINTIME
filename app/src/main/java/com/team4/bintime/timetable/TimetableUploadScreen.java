package com.team4.bintime.timetable;

import com.team4.bintime.data.recognition.RecognitionFailure;
import com.team4.bintime.data.repository.TimetableImageFailure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [BT-025/026] 업로드 화면이 그대로 그리는 스냅샷(불변). 선택한 이미지별 오류, 업로드 진행 상태, 인식 진행·실패,
 * 그리고 분석 버튼을 켤지({@link #canAnalyze})를 함께 담는다. 버튼 활성 조건은 화면이 따로 계산하지 않는다.
 * 이미지 내용·URI·인식 결과는 담지 않는다.
 */
final class TimetableUploadScreen {

    /** 선택한 이미지 수. */
    final int count;
    /** 선택 index별 오류(없으면 null). 선택 시 검사와 준비 단계에서 찾은 오류를 합친 것. 길이 = count. */
    final List<TimetableImageFailure.Code> imageFailures;
    /** 선택 시 검사가 아직 끝나지 않은 이미지가 있다. */
    final boolean checking;
    /** 업로드 진행 상태. */
    final TimetableUploadState upload;
    /** [BT-026] analyzeTimetable 호출 중. */
    final boolean recognizing;
    /** [BT-026] 마지막 인식 실패. 없으면 null. */
    final RecognitionFailure recognitionFailure;
    /** 분석 시작(또는 이미 인식한 선택으로 검수 재진입·인식 재시도)이 가능하다. */
    final boolean canAnalyze;

    TimetableUploadScreen(List<TimetableImageFailure.Code> imageFailures, boolean checking,
                          TimetableUploadState upload, boolean recognizing, RecognitionFailure recognitionFailure,
                          boolean canAnalyze) {
        this.imageFailures = Collections.unmodifiableList(new ArrayList<>(imageFailures));
        this.count = this.imageFailures.size();
        this.checking = checking;
        this.upload = upload == null ? TimetableUploadState.idle() : upload;
        this.recognizing = recognizing;
        this.recognitionFailure = recognitionFailure;
        this.canAnalyze = canAnalyze;
    }

    static TimetableUploadScreen empty() {
        return new TimetableUploadScreen(Collections.emptyList(), false, TimetableUploadState.idle(), false, null,
                false);
    }

    boolean hasImageFailures() {
        for (TimetableImageFailure.Code code : imageFailures) if (code != null) return true;
        return false;
    }

    boolean isBusy() {
        return checking || upload.isBusy() || recognizing;
    }
}
