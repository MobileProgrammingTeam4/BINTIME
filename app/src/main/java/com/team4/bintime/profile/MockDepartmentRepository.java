package com.team4.bintime.profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Sprint 1 목업용 학과 목록 (Figma 116:2685 학과 검색).
 * 시연용 고정 데이터이며 실제 학교 학과 체계가 아니다. 실제 목록 연동은 BT-015 범위.
 */
public final class MockDepartmentRepository {

    public static final class Department {
        public final String name;
        public final String college;

        Department(String name, String college) {
            this.name = name;
            this.college = college;
        }
    }

    private static final List<Department> ALL = Collections.unmodifiableList(Arrays.asList(
            new Department("소프트웨어학과", "소프트웨어융합대학 소프트웨어학부"),
            new Department("인공지능학과", "소프트웨어융합대학 소프트웨어학부"),
            new Department("컴퓨터공학과", "공과대학 전자정보통신공학부"),
            new Department("전자공학과", "공과대학 전자정보통신공학부"),
            new Department("기계공학과", "공과대학 기계시스템공학부"),
            new Department("시각디자인학과", "조형대학 디자인학부"),
            new Department("산업디자인학과", "조형대학 디자인학부"),
            new Department("경영학과", "경영대학 경영학부"),
            new Department("경제학과", "사회과학대학 경제학부"),
            new Department("국어국문학과", "인문대학 한국어문학부"),
            new Department("영어영문학과", "인문대학 영미어문학부"),
            new Department("수학과", "자연과학대학 수리과학부")));

    private MockDepartmentRepository() {}

    /** 학과명 또는 소속에 검색어가 포함된 학과. 검색어가 비어 있으면 전체. */
    public static List<Department> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return ALL;
        List<Department> result = new ArrayList<>();
        for (Department d : ALL) {
            if (d.name.toLowerCase(Locale.ROOT).contains(q) || d.college.toLowerCase(Locale.ROOT).contains(q)) {
                result.add(d);
            }
        }
        return result;
    }
}
