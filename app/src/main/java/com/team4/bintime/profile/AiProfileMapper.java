package com.team4.bintime.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BT-029 AI 자기소개: 요청 값 정리·응답 해석·오류 문구. Android·Firebase 의존성이 없어 단위 테스트로 검증한다.
 * 서버 계약(functions/src/ai/tasks/generateProfile.ts, docs/BT-024.md)과 길이 제한을 맞춘다.
 */
public final class AiProfileMapper {

    static final int DEPARTMENT_MAX = 40;
    static final int ACTIVITY_MAX_LENGTH = 20;
    static final int ACTIVITIES_MAX = 10;
    static final int COURSE_MAX_LENGTH = 60;
    static final int COURSES_MAX = 20;
    static final int SUGGESTED_TAGS_MAX = 5;
    /** 안내 카드에 이름을 보여 주는 과목 수 */
    static final int FEEDBACK_COURSES = 2;

    public static final String MSG_GENERIC = "AI 작성에 실패했어요. 직접 입력해 주세요";

    private AiProfileMapper() {}

    /** "3학년" → 3. 숫자가 없거나 1~6 밖이면 0. */
    public static int gradeNumber(String grade) {
        if (grade == null) return 0;
        String digits = grade.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 1) return 0;
        int value = Integer.parseInt(digits);
        return value >= 1 && value <= 6 ? value : 0;
    }

    /** 요청 전 확인. 문제가 있으면 사용자에게 보여 줄 문구, 없으면 null. */
    public static String validate(AiProfileRequest request) {
        if (request.department == null || request.department.trim().isEmpty()) return "학과를 먼저 선택해 주세요";
        if (request.grade < 1 || request.grade > 6) return "학년을 선택해 주세요";
        return null;
    }

    /** 태그 저장 형식: 앞의 `#`·공백 제거, 연속 공백 하나로. 비면 null. */
    public static String normalizeTag(String raw) {
        if (raw == null) return null;
        String tag = raw.replaceFirst("^[#\\s]+", "").replaceAll("\\s+", " ").trim();
        return tag.isEmpty() ? null : tag;
    }

    /** `#` 제거·중복(대소문자 무시) 제거·길이 초과 제외·최대 개수. */
    static List<String> normalizeTags(List<?> raw, int maxLength, int maxCount) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : raw) {
            if (!(item instanceof String)) continue;
            String tag = normalizeTag((String) item);
            if (tag == null || tag.length() > maxLength) continue;
            if (!seen.add(tag.toLowerCase(Locale.ROOT))) continue;
            result.add(tag);
            if (result.size() == maxCount) break;
        }
        return result;
    }

    /** Callable 입력. 서버 스키마 범위 안으로 정리한다(넘치면 버리거나 자른다). */
    public static Map<String, Object> toCallableData(AiProfileRequest request) {
        Map<String, Object> data = new HashMap<>();
        String department = request.department.trim();
        data.put("department", department.length() > DEPARTMENT_MAX ? department.substring(0, DEPARTMENT_MAX) : department);
        data.put("grade", request.grade);
        data.put("preferredActivities", normalizeTags(request.preferredActivities, ACTIVITY_MAX_LENGTH, ACTIVITIES_MAX));
        data.put("courseNames", courseNames(request.courseNames));
        String bio = request.draftBio == null ? "" : request.draftBio.trim();
        if (bio.length() > ProfileValidator.BIO_MAX_LENGTH) bio = bio.substring(0, ProfileValidator.BIO_MAX_LENGTH);
        if (!bio.isEmpty()) data.put("draftBio", bio);
        return data;
    }

    /** 수강 과목 이름: 공백 정리, 중복 제거, 60자 넘으면 자름, 최대 20개. */
    static List<String> courseNames(List<String> names) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null) continue;
            String trimmed = name.replaceAll("\\s+", " ").trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > COURSE_MAX_LENGTH) trimmed = trimmed.substring(0, COURSE_MAX_LENGTH);
            if (!seen.add(trimmed)) continue;
            result.add(trimmed);
            if (result.size() == COURSES_MAX) break;
        }
        return result;
    }

    /**
     * Callable 결과({@code {task, data:{bio, suggestedTags}, meta}}) → 초안. 형식이 맞지 않으면 null.
     * 서버가 검증하지만 앱에서도 길이·태그 형식을 한 번 더 맞춘다.
     */
    public static AiProfileDraft fromCallableResult(Object result) {
        if (!(result instanceof Map)) return null;
        Object data = ((Map<?, ?>) result).get("data");
        if (!(data instanceof Map)) return null;
        Object bioValue = ((Map<?, ?>) data).get("bio");
        if (!(bioValue instanceof String)) return null;
        String bio = ((String) bioValue).trim();
        if (bio.isEmpty() || bio.length() > ProfileValidator.BIO_MAX_LENGTH) return null;
        Object tags = ((Map<?, ?>) data).get("suggestedTags");
        List<String> suggested = normalizeTags(tags instanceof List ? (List<?>) tags : null,
                ACTIVITY_MAX_LENGTH, SUGGESTED_TAGS_MAX);
        return new AiProfileDraft(bio, suggested);
    }

    /** 서버 오류 사유(details.reason, docs/BT-024.md) → 사용자 문구. 어느 경우든 직접 입력은 계속할 수 있다. */
    public static String errorMessage(String reason) {
        if (reason == null) return MSG_GENERIC;
        switch (reason) {
            case "UNAUTHENTICATED":
                return "로그인이 필요해요. 다시 로그인해 주세요";
            case "PERMISSION_DENIED":
                return "학교 이메일로 인증한 계정만 AI 작성을 쓸 수 있어요";
            case "QUOTA_EXCEEDED":
                return "오늘 AI 작성 횟수를 모두 썼어요. 직접 입력해 주세요";
            case "AI_RATE_LIMITED":
                return "AI 요청이 많아요. 잠시 후 다시 시도해 주세요";
            case "AI_TIMEOUT":
            case "AI_INVALID_OUTPUT":
                return "AI가 응답하지 못했어요. 다시 시도해 주세요";
            case "AI_BLOCKED":
                return "AI가 작성할 수 없는 내용이에요. 직접 입력해 주세요";
            case "AI_UNAVAILABLE":
            case "AI_NOT_IMPLEMENTED":
                return "지금은 AI 작성을 쓸 수 없어요. 직접 입력해 주세요";
            default:
                return MSG_GENERIC;
        }
    }

    /** 결과 안내 카드 문구(Figma 41:10218 카드). 실제로 보낸 과목 이름을 쓴다. */
    public static String feedbackText(List<String> courseNames) {
        List<String> shown = new ArrayList<>();
        for (String name : courseNames) {
            if (name == null || name.trim().isEmpty()) continue;
            shown.add("‘" + name.trim() + "’");
            if (shown.size() == FEEDBACK_COURSES) break;
        }
        if (shown.isEmpty()) return "학과와 선택한 활동을 바탕으로 작성했어요";
        return "시간표의 " + String.join(", ", shown) + " 수업을 바탕으로 작성했어요";
    }
}
