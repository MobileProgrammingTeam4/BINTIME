package com.team4.bintime.data;

import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 1주차 목업 데이터 소스.
 * shortcut: 메모리 고정 데이터, 2주차에 Firestore 매칭 목록 조회로 교체 (search 시그니처 유지).
 */
public final class MockMatchRepository {

    /** 내 공강 슬롯이 있는지 (C 시간표 기능 연동 전 목업). */
    public static boolean hasFreeSlot = true;
    public static final String MY_SLOT_LABEL = "오늘(월) 14:00 ~ 15:30";

    private static final List<MatchCandidate> CANDIDATES = Arrays.asList(
            new MatchCandidate("u1", "김지민", "컴퓨터공학과", 3, "여성", 96, "공강 동선 과목일치",
                    "14:00", "15:30", "스터디",
                    Arrays.asList("#전공스터디", "#카페", "#산책", "#노래부르기"),
                    Arrays.asList("📍 직전 수업이 같은 '공학관'이에요 (도보 1분)", "📚 이번 학기 '자료구조' 수업을 함께 듣고 있어요"),
                    "같이 공부할 친구 찾아요! 공강에 가볍게 커피 한 잔도 좋아해요 :)")
                    .detail("복지관 지하1층 카페 / 북악도서관", 96, Arrays.asList(
                            new MatchCandidate.Review("⏰ 시간 약속을 잘 지켜요", 12),
                            new MatchCandidate.Review("⚡ 답장이 빠르고 장소 정하기 수월했어요", 5),
                            new MatchCandidate.Review("🏃 다음 수업 시간에 맞춰 깔끔하게 헤어졌어요", 3),
                            new MatchCandidate.Review("🔥 공부에 집중할 수 있었어요", 1))),
            new MatchCandidate("u2", "이서준", "소프트웨어학부", 2, "남성", 91, "공강 과목일치",
                    "13:30", "15:30", "스터디",
                    Arrays.asList("#코딩스터디", "#자료구조", "#카페"),
                    Arrays.asList("📚 '자료구조' 과제를 같이 할 수 있어요", "📍 다음 수업 강의실이 같은 층이에요"),
                    "알고리즘 문제 같이 풀 사람! 백준 골드 목표입니다.")
                    .detail("미래관 4층 스터디라운지", 0, Collections.emptyList()),
            new MatchCandidate("u3", "박하은", "경영학과", 4, "여성", 84, "공강 동선일치",
                    "12:00", "13:00", "밥",
                    Arrays.asList("#밥", "#카페", "#산책"),
                    Arrays.asList("📍 두 사람 모두 '북악관'에서 수업이 끝나요", "🍚 점심시간 공강이 정확히 겹쳐요"),
                    "학식 같이 먹을 친구 구해요~ 맛집 탐방도 좋아요!")
                    .detail("법학관 학생식당", 88, Arrays.asList(
                            new MatchCandidate.Review("🍚 같이 밥 먹기 편했어요", 7),
                            new MatchCandidate.Review("⏰ 시간 약속을 잘 지켜요", 4))),
            new MatchCandidate("u4", "최민호", "전자공학부", 3, "남성", 78, "공강 일치",
                    "14:00", "16:30", "카페·디저트",
                    Arrays.asList("#카페", "#코인노래방"),
                    Arrays.asList("☕ 선호 활동 '#카페'가 같아요", "⏰ 공강이 2시간 넘게 겹쳐요"),
                    "디저트 카페 투어 좋아합니다. 공강에 노래방도 OK"),
            new MatchCandidate("u5", "정유나", "시각디자인학과", 1, "여성", 72, "공강 동선일치",
                    "15:00", "16:30", "산책",
                    Arrays.asList("#산책", "#카페"),
                    Arrays.asList("📍 직전 수업 건물이 가까워요 (도보 3분)", "🌿 교내 산책로 근처에서 만날 수 있어요"),
                    "새내기예요! 학교 구경 겸 같이 산책해요 :)"),
            new MatchCandidate("u6", "한도윤", "기계공학과", 2, "남성", 65, "공강 일치",
                    "11:00", "12:00", "스터디",
                    Arrays.asList("#전공스터디", "#C++ 프로그래밍"),
                    Arrays.asList("📚 'C++ 프로그래밍' 수강 이력이 있어요", "⏰ 오전 공강이 1시간 겹쳐요"),
                    "C++ 과제 같이 고민해요. 도서관 스터디룸 선호합니다.")
    );

    private MockMatchRepository() {
    }

    /** uid 로 후보 찾기 (프로필 상세 화면용). 없으면 null. */
    public static MatchCandidate find(String uid) {
        for (MatchCandidate c : CANDIDATES) if (c.uid.equals(uid)) return c;
        return null;
    }

    /** 최소 겹침 시간·목적 조건을 만족하는 후보를 AI 매칭률 높은 순으로 반환. 태그는 거르지 않고 카드에서 강조만 한다. */
    public static List<MatchCandidate> search(MatchFilter filter) {
        List<MatchCandidate> result = new ArrayList<>();
        for (MatchCandidate c : CANDIDATES) {
            boolean purposeOk = MatchFilter.PURPOSE_ALL.equals(filter.purpose) || filter.purpose.equals(c.purpose);
            if (c.overlapMinutes() >= filter.minOverlapMinutes && purposeOk) result.add(c);
        }
        result.sort((a, b) -> Integer.compare(b.matchRate, a.matchRate));
        return result;
    }
}
