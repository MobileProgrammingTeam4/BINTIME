package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BT-029: AI 자기소개 요청 정리·응답 해석·오류 문구. 서버 계약(docs/BT-024.md generateProfile)과 맞춘다. */
public class AiProfileMapperTest {

    private static AiProfileRequest request(String department, int grade, List<String> activities,
                                            List<String> courses, String bio) {
        return new AiProfileRequest(department, grade, activities, courses, bio);
    }

    @Test
    public void gradeNumber_parsesScreenValue() {
        assertEquals(1, AiProfileMapper.gradeNumber("1학년"));
        assertEquals(4, AiProfileMapper.gradeNumber("4학년"));
        assertEquals(0, AiProfileMapper.gradeNumber(null));
        assertEquals(0, AiProfileMapper.gradeNumber("학년"));
        assertEquals(0, AiProfileMapper.gradeNumber("0학년"));
        assertEquals(0, AiProfileMapper.gradeNumber("12학년"));
    }

    @Test
    public void validate_requiresDepartmentAndGrade() {
        assertEquals("학과를 먼저 선택해 주세요", AiProfileMapper.validate(request("", 2, list(), list(), null)));
        assertEquals("학과를 먼저 선택해 주세요", AiProfileMapper.validate(request(null, 2, list(), list(), null)));
        assertEquals("학년을 선택해 주세요", AiProfileMapper.validate(request("소프트웨어학과", 0, list(), list(), null)));
        assertNull(AiProfileMapper.validate(request("소프트웨어학과", 3, list(), list(), null)));
    }

    /** 개인정보 최소 전송: 계약에 있는 5개 키만, 빈 소개는 보내지 않는다. 이름·이메일·UID 키가 없다. */
    @Test
    public void toCallableData_sendsOnlyContractFields() {
        Map<String, Object> data = AiProfileMapper.toCallableData(
                request(" 소프트웨어학과 ", 3, list("#카페", "카페", "스터디"), list("자료구조", " 자료구조 ", "운영체제"), "  "));
        assertEquals(new java.util.TreeSet<>(Arrays.asList("courseNames", "department", "grade", "preferredActivities")),
                new java.util.TreeSet<>(data.keySet()));
        assertEquals("소프트웨어학과", data.get("department"));
        assertEquals(3, data.get("grade"));
        assertEquals(list("카페", "스터디"), data.get("preferredActivities"));
        assertEquals(list("자료구조", "운영체제"), data.get("courseNames"));

        Map<String, Object> withBio = AiProfileMapper.toCallableData(request("학과", 1, list(), list(), " 안녕하세요 "));
        assertEquals("안녕하세요", withBio.get("draftBio"));
    }

    /** 서버 스키마 상한: 학과 40자, 활동 10개·20자, 과목 20개·60자, 소개 150자. */
    @Test
    public void toCallableData_clampsToServerLimits() {
        List<String> activities = new ArrayList<>();
        for (int i = 0; i < 12; i++) activities.add("활동" + i);
        activities.add("가".repeat(21));
        List<String> courses = new ArrayList<>();
        for (int i = 0; i < 25; i++) courses.add("과목" + i);
        courses.add(0, "나".repeat(70));

        Map<String, Object> data = AiProfileMapper.toCallableData(
                request("다".repeat(50), 2, activities, courses, "라".repeat(200)));
        assertEquals(40, ((String) data.get("department")).length());
        assertEquals(10, ((List<?>) data.get("preferredActivities")).size());
        List<?> sentCourses = (List<?>) data.get("courseNames");
        assertEquals(20, sentCourses.size());
        assertEquals(60, ((String) sentCourses.get(0)).length());
        assertEquals(150, ((String) data.get("draftBio")).length());
    }

    @Test
    public void fromCallableResult_readsDraftAndNormalizesTags() {
        AiProfileDraft draft = AiProfileMapper.fromCallableResult(result(" 자료구조 같이 공부해요 ",
                list("#코딩스터디", "코딩스터디", "# 도서관", "", "가".repeat(21), "산책", "카페", "밥")));
        assertNotNull(draft);
        assertEquals("자료구조 같이 공부해요", draft.bio);
        assertEquals(list("코딩스터디", "도서관", "산책", "카페", "밥"), draft.suggestedTags);
    }

    @Test
    public void fromCallableResult_rejectsMalformed() {
        assertNull(AiProfileMapper.fromCallableResult(null));
        assertNull(AiProfileMapper.fromCallableResult("text"));
        assertNull(AiProfileMapper.fromCallableResult(Collections.singletonMap("data", "x")));
        assertNull(AiProfileMapper.fromCallableResult(result("", list())));
        assertNull(AiProfileMapper.fromCallableResult(result("가".repeat(151), list())));
        Map<String, Object> noTags = new HashMap<>();
        noTags.put("bio", "안녕하세요");
        AiProfileDraft draft = AiProfileMapper.fromCallableResult(Collections.singletonMap("data", noTags));
        assertNotNull(draft);
        assertTrue(draft.suggestedTags.isEmpty());
    }

    /** 오류가 나도 직접 입력을 이어갈 수 있게, 사유별로 다음 행동을 알려 준다(docs/BT-024.md 오류 표). */
    @Test
    public void errorMessage_perReason() {
        assertTrue(AiProfileMapper.errorMessage("QUOTA_EXCEEDED").contains("직접 입력"));
        assertTrue(AiProfileMapper.errorMessage("AI_TIMEOUT").contains("다시 시도"));
        assertTrue(AiProfileMapper.errorMessage("AI_RATE_LIMITED").contains("잠시 후"));
        assertTrue(AiProfileMapper.errorMessage("AI_UNAVAILABLE").contains("직접 입력"));
        assertTrue(AiProfileMapper.errorMessage("PERMISSION_DENIED").contains("학교 이메일"));
        assertEquals(AiProfileMapper.MSG_GENERIC, AiProfileMapper.errorMessage(null));
        assertEquals(AiProfileMapper.MSG_GENERIC, AiProfileMapper.errorMessage("SOMETHING_NEW"));
    }

    @Test
    public void feedbackText_namesUpToTwoCourses() {
        assertEquals("시간표의 ‘자료구조’, ‘운영체제’ 수업을 바탕으로 작성했어요",
                AiProfileMapper.feedbackText(list("자료구조", "운영체제", "알고리즘")));
        assertEquals("시간표의 ‘자료구조’ 수업을 바탕으로 작성했어요", AiProfileMapper.feedbackText(list("자료구조", " ")));
        assertEquals("학과와 선택한 활동을 바탕으로 작성했어요", AiProfileMapper.feedbackText(list()));
    }

    @Test
    public void tagRows_aiRowShowsSuggestedAndCustomSelected() {
        List<String> defaults = list("스터디", "카페", "산책");
        // 추천 중 기본 목록에 있는 '카페'는 아래 줄에만, 예전에 고른 '보드게임'은 AI 줄에 남는다.
        assertEquals(list("코딩스터디", "도서관", "보드게임"),
                ProfileTagRows.aiRow(list("코딩스터디", "카페", "도서관"), list("스터디", "보드게임", "코딩스터디"), defaults));
        assertTrue(ProfileTagRows.aiRow(list(), list("스터디"), defaults).isEmpty());
        assertFalse(ProfileTagRows.aiRow(list(), list("보드게임"), defaults).isEmpty());
    }

    private static Map<String, Object> result(String bio, List<String> tags) {
        Map<String, Object> data = new HashMap<>();
        data.put("bio", bio);
        data.put("suggestedTags", tags);
        Map<String, Object> res = new HashMap<>();
        res.put("task", "generateProfile");
        res.put("data", data);
        return res;
    }

    private static List<String> list(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }
}
