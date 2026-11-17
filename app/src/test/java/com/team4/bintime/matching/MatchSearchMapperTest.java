package com.team4.bintime.matching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
        assertEquals(0, c.matchRate);
        assertEquals("profileImages/u9/profile.jpg", c.photoPath);
        assertTrue(c.reasons.isEmpty());
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
        assertEquals("추천 활동: 카페", c.matchReason);
        assertEquals(Collections.singletonList("공강이 길게 겹쳐요"), c.reasons);
        raw.put("score", 150);
        assertEquals(100, MatchSearchMapper.toCandidate(raw, new UserProfile(), 2, "2026-11-16").matchRate);
    }

    @Test
    public void candidates_형식이틀린항목은버림() {
        Map<String, Object> res = new HashMap<>();
        List<Object> list = Arrays.asList(Collections.singletonMap("candidateId", "a"), "bad");
        res.put("candidates", list);
        assertEquals(1, MatchSearchMapper.candidates(res).size());
    }
}
