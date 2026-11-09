package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MockDepartmentRepositoryTest {

    @Test
    public void emptyQuery_returnsAll() {
        assertEquals(MockDepartmentRepository.search(null).size(), MockDepartmentRepository.search("  ").size());
        assertFalse(MockDepartmentRepository.search("").isEmpty());
    }

    @Test
    public void search_matchesNameOrCollege() {
        List<MockDepartmentRepository.Department> byName = MockDepartmentRepository.search("소프트웨어");
        assertTrue(byName.stream().anyMatch(d -> d.name.equals("소프트웨어학과")));

        List<MockDepartmentRepository.Department> byCollege = MockDepartmentRepository.search("조형대학");
        assertFalse(byCollege.isEmpty());
        assertTrue(byCollege.stream().allMatch(d -> d.college.contains("조형대학")));
    }

    @Test
    public void search_noMatch_returnsEmpty() {
        assertTrue(MockDepartmentRepository.search("없는학과xyz").isEmpty());
    }
}
