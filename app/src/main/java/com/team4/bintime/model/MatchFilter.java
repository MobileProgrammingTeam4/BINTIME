package com.team4.bintime.model;

import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 매칭 검색 조건 (필터 설정 화면 ↔ 매칭 메인 화면 사이에서 Intent로 전달). */
public class MatchFilter implements Serializable {

    /** 기획안: 최소 겹침 60·90·120·150·180분 중 선택 (기본 60분). */
    public static final int[] OVERLAP_OPTIONS = {60, 90, 120, 150, 180};
    public static final String PURPOSE_ALL = "전체";
    public static final List<String> PURPOSE_OPTIONS = Arrays.asList(PURPOSE_ALL, "스터디", "밥", "카페·디저트", "산책");
    // shortcut: AI 추천 태그는 3주차 Gemini 연동 전까지 고정 목업
    public static final List<String> AI_TAGS = Arrays.asList("#코딩스터디", "#자료구조", "#C++ 프로그래밍", "#코인노래방");
    public static final List<String> BASE_TAGS = Arrays.asList("#스터디", "#카페", "#산책", "#밥", "#전공스터디");

    public int minOverlapMinutes = 60;
    public String purpose = PURPOSE_ALL;
    public Set<String> tags = new LinkedHashSet<>();

    public static MatchFilter defaults() {
        MatchFilter f = new MatchFilter();
        // shortcut: 내 프로필 선호 태그 목업, B 프로필 데이터 연동 시 교체
        f.tags.addAll(Arrays.asList("#코딩스터디", "#자료구조", "#스터디", "#카페", "#산책"));
        return f;
    }

    public MatchFilter copy() {
        MatchFilter f = new MatchFilter();
        f.minOverlapMinutes = minOverlapMinutes;
        f.purpose = purpose;
        f.tags = new LinkedHashSet<>(tags);
        return f;
    }
    /** 필터 중복 사용 - 객체 낭비생성 (수정?)

    /** 90 → "1시간 30분", 60 → "1시간", 45 → "45분" */
    public static String formatDuration(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + "분";
        return m == 0 ? h + "시간" : h + "시간 " + m + "분";
    }
}
