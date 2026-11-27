package com.team4.bintime.matching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.profile.UserProfile;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MatchSearchMapperTest {

    @Test
    public void settingsDocument_전체는빈목록_태그는샵없이() {
        MatchFilter f = new MatchFilter();
        f.minOverlapMinutes = 90;
        f.tags.addAll(Arrays.asList("#카페", "산책", "#카페", "# "));
        Map<String, Object> doc = MatchSearchMapper.settingsDocument(true, f);
        assertEquals(true, doc.get("isMatching"));
        assertEquals(90, doc.get("minOverlapMinutes"));
        assertEquals(Collections.emptyList(), doc.get("purposes"));
        assertEquals(Arrays.asList("카페", "산책"), doc.get("preferredTags"));

        f.purpose = "스터디";
        assertEquals(Collections.singletonList("스터디"), MatchSearchMapper.settingsDocument(false, f).get("purposes"));
    }

    @Test
    public void status_모르는값은null() {
        assertEquals(MatchSearchRepository.Status.NO_FREE_SLOT, MatchSearchMapper.status(Collections.singletonMap("status", "NO_FREE_SLOT")));
        assertNull(MatchSearchMapper.status(Collections.singletonMap("status", "WHAT")));
        assertNull(MatchSearchMapper.status(null));
    }

    @Test
    public void slotLabel_오늘과다른날() {
        Map<String, Object> ref = new HashMap<>();
        ref.put("meetingDate", "2026-11-16");
        ref.put("dayOfWeek", 1);
        ref.put("startMinute", 850L); // Callable 숫자는 Long 으로 올 수 있다
        ref.put("endMinute", 960.0);
        Map<String, Object> res = Collections.singletonMap("reference", ref);
        assertEquals("오늘(월) 14:10 ~ 16:00", MatchSearchMapper.slotLabel(res, "2026-11-16"));
        assertEquals("11/16(월) 14:10 ~ 16:00", MatchSearchMapper.slotLabel(res, "2026-11-15"));
    }

    @Test
    public void toCandidate_프로필과서버값결합() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("candidateId", "u9");
        raw.put("purpose", "밥");
        raw.put("meetingDate", "2026-11-17");
        raw.put("startMinute", 720);
        raw.put("endMinute", 810);
        UserProfile p = new UserProfile();
        p.setNickname("민지");
        p.setDepartment("경영학과");
        p.setGrade("2학년");
        p.setGender("여성");
        p.setBio("안녕");
        p.setInterestTags(Arrays.asList("카페", "#산책"));
        p.setPhotoPath("profileImages/u9/profile.jpg");
        MatchCandidate c = MatchSearchMapper.toCandidate(raw, p, 2, "2026-11-16");
        assertEquals("u9", c.uid);
        assertEquals(2, c.grade);
        assertEquals(90, c.overlapMinutes());
        assertEquals("11/17(화)", c.dayLabel);
        assertEquals(Arrays.asList("#카페", "#산책"), c.tags);
        assertEquals(MatchCandidate.NO_SCORE, c.matchRate);
        assertFalse(c.hasScore());
        assertEquals("profileImages/u9/profile.jpg", c.photoPath);
        assertTrue(c.reasons.isEmpty());
        assertNull(c.aiPlace); // 서버가 장소를 안 주면 줄 숨김
    }

    @Test
    public void toCandidate_검색추천장소() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("candidateId", "u9");
        raw.put("meetingDate", "2026-11-17");
        raw.put("placeName", "공차 (북악관 1층)");
        raw.put("placeCount", 5L);
        assertEquals("공차 (북악관 1층) 외 4곳", MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16").aiPlace);
        raw.put("placeCount", 1);
        assertEquals("공차 (북악관 1층)", MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16").aiPlace);
        raw.put("placeName", "");
        assertNull(MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16").aiPlace);
    }

    @Test
    public void toCandidate_AI점수와이유() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("candidateId", "u9");
        raw.put("purpose", "밥");
        raw.put("meetingDate", "2026-11-17");
        raw.put("startMinute", 720);
        raw.put("endMinute", 810);
        raw.put("score", 82L);
        raw.put("reasons", Arrays.asList("공강이 길게 겹쳐요", 3));
        raw.put("suggestedActivity", "카페");
        MatchCandidate c = MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16");
        assertEquals(82, c.matchRate);
        assertEquals("", c.matchReason);
        assertEquals(Arrays.asList("공강이 길게 겹쳐요", "추천 활동: 카페"), c.reasons);
        raw.put("score", 150);
        assertEquals(100, MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16").matchRate);
        raw.remove("reasons"); // 이유가 없어도 추천 활동 줄은 붙는다
        assertEquals(Collections.singletonList("추천 활동: 카페"),
                MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16").reasons);
    }

    @Test
    public void toCandidate_0점은표시_점수없음은숨김() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("candidateId", "u9");
        raw.put("meetingDate", "2026-11-17");
        raw.put("score", 0L);
        MatchCandidate zero = MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16");
        assertEquals(0, zero.matchRate);
        assertTrue(zero.hasScore());
        raw.put("score", "82"); // 숫자가 아니면 점수 없음
        assertFalse(MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16").hasScore());
        raw.remove("score");
        assertFalse(MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16").hasScore());
    }

    @Test
    public void candidates_형식이틀린항목은버림() {
        Map<String, Object> res = new HashMap<>();
        List<Object> list = Arrays.asList(Collections.singletonMap("candidateId", "a"), "bad");
        res.put("candidates", list);
        assertEquals(1, MatchSearchMapper.candidates(res).size());
    }
}
