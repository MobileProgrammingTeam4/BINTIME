package com.team4.bintime.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;

import org.junit.Test;

import java.util.List;

public class MockMatchRepositoryTest {

    @Test
    public void defaultFilter_returnsAllSortedByMatchRate() {
        List<MatchCandidate> r = MockMatchRepository.search(MatchFilter.defaults());
        assertEquals(6, r.size());
        for (int i = 1; i < r.size(); i++) assertTrue(r.get(i - 1).matchRate >= r.get(i).matchRate);
    }

    @Test
    public void minOverlap_filtersShortSlots() {
        MatchFilter f = MatchFilter.defaults();
        f.minOverlapMinutes = 120;
        for (MatchCandidate c : MockMatchRepository.search(f)) assertTrue(c.overlapMinutes() >= 120);
        f.minOverlapMinutes = 180;
        assertTrue(MockMatchRepository.search(f).isEmpty()); // '결과 없음' 시연 조건
    }

    @Test
    public void purpose_filtersExactly() {
        MatchFilter f = MatchFilter.defaults();
        f.purpose = "밥";
        List<MatchCandidate> r = MockMatchRepository.search(f);
        assertEquals(1, r.size());
        assertEquals("박하은", r.get(0).name);
    }

    @Test
    public void formatDuration() {
        assertEquals("1시간", MatchFilter.formatDuration(60));
        assertEquals("1시간 30분", MatchFilter.formatDuration(90));
        assertEquals("45분", MatchFilter.formatDuration(45));
    }

    @Test
    public void requestMatch_blocksDuplicate() {
        assertTrue(MockMatchRepository.requestMatch("u5"));
        assertTrue(MockMatchRepository.isRequested("u5"));
        assertFalse(MockMatchRepository.requestMatch("u5")); // 같은 상대에게 두 번 신청 불가
    }

    @Test
    public void find_returnsCandidateOrNull() {
        assertEquals("김지민", MockMatchRepository.find("u1").name);
        assertNull(MockMatchRepository.find("없는uid"));
    }
}
