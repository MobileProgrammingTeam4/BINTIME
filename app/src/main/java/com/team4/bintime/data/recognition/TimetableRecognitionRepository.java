package com.team4.bintime.data.recognition;

import java.util.List;

/**
 * [BT-026] 시간표 이미지 인식 계약. 앱은 Gemini 를 직접 부르지 않고 analyzeTimetable Callable 만 부른다
 * (P4 Firebase 구현). 입력은 BT-025 에서 올린 본인 Storage 경로(1~5개, 선택 순서 = sourceImageIndex)다.
 * 결과는 검수용 초안이며 저장소에 쓰지 않는다. 콜백은 main 스레드에서 부른다. Mock 은 즉시(동기) 부른다.
 */
public interface TimetableRecognitionRepository {

    interface Registration {
        /** 결과를 더 받지 않는다. 서버 호출 자체는 취소되지 않을 수 있다. */
        void cancel();
    }

    interface Callback {
        void onSuccess(RecognitionResult result);

        void onError(RecognitionFailure failure);
    }

    Registration analyze(List<String> imagePaths, Callback callback);
}
