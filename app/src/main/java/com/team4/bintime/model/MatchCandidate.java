package com.team4.bintime.model;

import java.util.List;

/**
 * 매칭 후보(상대 사용자) 1명의 카드 데이터.
 * 2주차에 Firestore 문서와 같은 필드명으로 매핑할 예정.
 */
public class MatchCandidate {
    public final String uid;
    public final String name;
    public final String department;
    public final int grade;
    public final String gender;
    /** AI 매칭률(%) 0~100. 점수가 없으면 {@link #NO_SCORE}. 표시·계산 전에는 {@link #hasScore()} 로 확인한다. */
    public final int matchRate;
    /** 서버가 점수를 주지 않음(상위 5명 밖·AI 실패). 0점과 구분한다(BT-027). */
    public static final int NO_SCORE = -1;
    public final String matchReason;     // 배지 아래 근거 요약 (예: 공강 동선 과목일치)
    public final String overlapStart;    // "HH:mm"
    public final String overlapEnd;      // "HH:mm"
    public final String purpose;         // MatchFilter.PURPOSE_OPTIONS 중 하나
    public final List<String> tags;
    public final List<String> reasons;   // AI 추천 이유 줄들
    public final String intro;
    /** 겹치는 공강 날짜 표시. 실데이터는 "오늘(월)" 또는 "11/17(화)" */
    public String dayLabel = "오늘";
    /** publicProfiles.photoPath (Storage 경로). 없으면 기본 이미지. */
    public String photoPath;

    // 프로필 상세 화면 전용 (없으면 해당 영역을 빈 상태로 표시)
    public String aiPlace;               // 추천 장소 줄. 실데이터는 BT-042 검색 미리보기(placeName·placeCount), 없으면 null
    public int rematchRate;              // 재만남 희망률(%)
    public List<Review> reviews = java.util.Collections.emptyList();

    /** 받은 매칭 후기 항목 1줄: "⏰ 시간 약속을 잘 지켜요" 12명 */
    public static class Review {
        public final String label;
        public final int count;

        public Review(String label, int count) {
            this.label = label;
            this.count = count;
        }
    }

    public MatchCandidate detail(String aiPlace, int rematchRate, List<Review> reviews) {
        this.aiPlace = aiPlace;
        this.rematchRate = rematchRate;
        this.reviews = reviews;
        return this;
    }

    public MatchCandidate(String uid, String name, String department, int grade, String gender,
                          int matchRate, String matchReason, String overlapStart, String overlapEnd,
                          String purpose, List<String> tags, List<String> reasons, String intro) {
        this.uid = uid;
        this.name = name;
        this.department = department;
        this.grade = grade;
        this.gender = gender;
        this.matchRate = matchRate;
        this.matchReason = matchReason;
        this.overlapStart = overlapStart;
        this.overlapEnd = overlapEnd;
        this.purpose = purpose;
        this.tags = tags;
        this.reasons = reasons;
        this.intro = intro;
    }

    public int overlapMinutes() {
        return toMinutes(overlapEnd) - toMinutes(overlapStart);
    }

    private static int toMinutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    /** 0점도 점수가 있는 것으로 본다. NO_SCORE 만 false. */
    public boolean hasScore() {
        return matchRate >= 0;
    }
}
