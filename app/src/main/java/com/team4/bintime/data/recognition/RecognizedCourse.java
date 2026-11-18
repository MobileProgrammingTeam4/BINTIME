package com.team4.bintime.data.recognition;

/**
 * [BT-026] analyzeTimetable 이 돌려준 수업 하나(검수 전 초안). 서버 출력 스키마
 * ({@code functions/src/ai/tasks/analyzeTimetable.ts})와 같은 필드다. 시간은 BT-012 규칙(자정 기준 분,
 * [startMinute, endMinute), dayOfWeek 1=월~7=일). 불변.
 * <p>
 * 수업명·건물·강의실은 개인정보가 섞일 수 있으므로 로그에 남기지 않는다({@link #toString()}도 값을 담지 않는다).
 */
public final class RecognizedCourse {

    public final String name;
    public final int dayOfWeek;
    public final int startMinute;
    public final int endMinute;
    /** 없으면 null. */
    public final String building;
    /** 없으면 null. */
    public final String room;
    /** 0~1. */
    public final double confidence;
    /** imagePaths 의 몇 번째 이미지에서 읽었는지(0부터). */
    public final int sourceImageIndex;

    public RecognizedCourse(String name, int dayOfWeek, int startMinute, int endMinute, String building,
                            String room, double confidence, int sourceImageIndex) {
        this.name = name;
        this.dayOfWeek = dayOfWeek;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.building = building;
        this.room = room;
        this.confidence = confidence;
        this.sourceImageIndex = sourceImageIndex;
    }

    @Override
    public String toString() {
        return "RecognizedCourse{day=" + dayOfWeek + ", " + startMinute + "-" + endMinute
                + ", confidence=" + confidence + ", image=" + sourceImageIndex + "}";
    }
}
