package com.team4.bintime.matching;

import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.profile.UserProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** matchSettings 문서·searchMatches 응답 ↔ 앱 모델 변환. Android·Firebase 의존이 없어 단위 테스트한다. */
final class MatchSearchMapper {

    static final int MAX_TAGS = 20;
    private static final String[] DAYS = {"", "월", "화", "수", "목", "금", "토", "일"};

    private MatchSearchMapper() {}

    /** matchSettings/{uid} (#77 A 결정). 목적 "전체"는 빈 목록, 태그는 # 없이 저장(BT-012). updatedAt 은 호출 쪽이 붙인다. */
    static Map<String, Object> settingsDocument(boolean matchingOn, MatchFilter filter) {
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : filter.tags) {
            String t = tag.startsWith("#") ? tag.substring(1).trim() : tag.trim();
            if (!t.isEmpty() && tags.size() < MAX_TAGS) tags.add(t);
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("isMatching", matchingOn);
        doc.put("minOverlapMinutes", filter.minOverlapMinutes);
        doc.put("purposes", MatchFilter.PURPOSE_ALL.equals(filter.purpose)
                ? Collections.emptyList() : Collections.singletonList(filter.purpose));
        doc.put("preferredTags", new ArrayList<>(tags));
        return doc;
    }

    /** 모르는 값이면 null(응답 형식 오류로 처리). */
    static MatchSearchRepository.Status status(Map<?, ?> response) {
        Object s = response == null ? null : response.get("status");
        for (MatchSearchRepository.Status v : MatchSearchRepository.Status.values()) if (v.name().equals(s)) return v;
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> candidates(Map<?, ?> response) {
        Object list = response.get("candidates");
        if (!(list instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : (List<Object>) list) if (item instanceof Map) out.add((Map<String, Object>) item);
        return out;
    }

    /** 오늘이면 "오늘(월)", 아니면 "11/17(화)". today 는 Asia/Seoul YYYY-MM-DD. */
    static String dayLabel(String meetingDate, int dayOfWeek, String today) {
        String day = dayOfWeek >= 1 && dayOfWeek <= 7 ? DAYS[dayOfWeek] : "?";
        if (meetingDate.equals(today)) return "오늘(" + day + ")";
        return Integer.parseInt(meetingDate.substring(5, 7)) + "/" + Integer.parseInt(meetingDate.substring(8, 10)) + "(" + day + ")";
    }

    static String hhmm(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    /** 응답의 reference → "오늘(월) 14:10 ~ 16:00" */
    static String slotLabel(Map<?, ?> response, String today) {
        Object ref = response.get("reference");
        if (!(ref instanceof Map)) return null;
        Map<?, ?> r = (Map<?, ?>) ref;
        return dayLabel((String) r.get("meetingDate"), num(r.get("dayOfWeek")), today)
                + " " + hhmm(num(r.get("startMinute"))) + " ~ " + hhmm(num(r.get("endMinute")));
    }

    /**
     * 서버 후보 1건 + 공개 프로필 → 카드. BT-027: 서버가 붙인 AI score·reasons·suggestedActivity 를 쓴다.
     * 점수가 없으면(상위 5명 밖·AI 실패) NO_SCORE(-1)라 카드에서 매칭률을 숨기고, 0점은 0%로 보여준다.
     */
    static MatchCandidate toCandidate(Map<String, Object> raw, UserProfile profile, int dayOfWeek, String today) {
        List<String> tags = new ArrayList<>();
        if (profile.getInterestTags() != null) for (String t : profile.getInterestTags()) tags.add(t.startsWith("#") ? t : "#" + t);
        Object rawScore = raw.get("score");
        int score = rawScore instanceof Number
                ? Math.max(0, Math.min(100, ((Number) rawScore).intValue())) : MatchCandidate.NO_SCORE;
        // 배지 아래(matchReason)는 짧은 태그 자리라 비우고, 문장인 추천 활동은 이유 칸 마지막 줄로 둔다(카드 배치 깨짐 방지).
        List<String> reasons = strings(raw.get("reasons"));
        Object activity = raw.get("suggestedActivity");
        if (activity instanceof String && !((String) activity).isEmpty()) reasons.add("추천 활동: " + activity);
        MatchCandidate c = new MatchCandidate((String) raw.get("candidateId"), profile.getNickname(),
                profile.getDepartment(), gradeNumber(profile.getGrade()), profile.getGender(), score, "",
                hhmm(num(raw.get("startMinute"))), hhmm(num(raw.get("endMinute"))), (String) raw.get("purpose"),
                tags, reasons, profile.getBio());
        c.dayLabel = dayLabel((String) raw.get("meetingDate"), dayOfWeek, today);
        c.photoPath = profile.getPhotoPath();
        return c;
    }

    /** "3학년" → 3, 숫자가 없으면 0 */
    static int gradeNumber(String grade) {
        String digits = grade == null ? "" : grade.replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    static List<String> strings(Object value) {
        List<String> out = new ArrayList<>(); // 호출부가 줄을 더 붙이므로 수정 가능한 목록
        if (!(value instanceof List)) return out;
        for (Object item : (List<?>) value) if (item instanceof String && !((String) item).isEmpty()) out.add((String) item);
        return out;
    }

    /** Callable 응답 숫자는 Integer/Long/Double 중 하나로 온다. */
    static int num(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
