package com.team4.bintime.data.recognition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [BT-026] analyzeTimetable 응답을 검사한 결과(불변). 형식이 틀린 수업은 빼고 {@link #droppedCount}에 센다.
 * 검수용 초안일 뿐이며 저장소에 쓰지 않는다.
 */
public final class RecognitionResult {

    public final List<RecognizedCourse> courses;
    /** 서버(모델)가 준 안내 문장. 화면에 그대로 보여 줄 수 있으나 로그에 남기지 않는다. */
    public final List<String> warnings;
    /** 형식이 틀려 버린 수업 수. */
    public final int droppedCount;
    /** 요청한 이미지 수(sourceImageIndex 범위). */
    public final int imageCount;

    public RecognitionResult(List<RecognizedCourse> courses, List<String> warnings, int droppedCount,
                             int imageCount) {
        this.courses = Collections.unmodifiableList(new ArrayList<>(courses));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.droppedCount = droppedCount;
        this.imageCount = imageCount;
    }

    @Override
    public String toString() {
        return "RecognitionResult{courses=" + courses.size() + ", warnings=" + warnings.size()
                + ", dropped=" + droppedCount + ", images=" + imageCount + "}";
    }
}
