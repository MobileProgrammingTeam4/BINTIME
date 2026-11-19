package com.team4.bintime.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * generateProfile 에 보내는 값 (BT-029, docs/BT-024.md 계약).
 * 이름·이메일·UID 는 담지 않는다. 학과·학년·선택한 활동 태그·수강 과목 이름·작성 중인 소개만.
 */
public final class AiProfileRequest {

    public final String department;
    /** 1~6. "3학년" 같은 화면 값은 {@link AiProfileMapper#gradeNumber} 로 바꾼다. */
    public final int grade;
    public final List<String> preferredActivities;
    public final List<String> courseNames;
    /** 비어 있으면 보내지 않는다. */
    public final String draftBio;

    public AiProfileRequest(String department, int grade, List<String> preferredActivities,
                            List<String> courseNames, String draftBio) {
        this.department = department;
        this.grade = grade;
        this.preferredActivities = Collections.unmodifiableList(new ArrayList<>(preferredActivities));
        this.courseNames = Collections.unmodifiableList(new ArrayList<>(courseNames));
        this.draftBio = draftBio;
    }
}
