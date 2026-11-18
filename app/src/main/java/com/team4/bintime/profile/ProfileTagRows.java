package com.team4.bintime.profile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 프로필 화면 태그 줄 구성 (BT-029). Android 의존성이 없어 단위 테스트로 검증한다. */
final class ProfileTagRows {

    private ProfileTagRows() {}

    /**
     * 'AI 추천' 줄에 보일 태그: AI 추천 태그(순서대로) + 이미 고른 태그 중 기본 목록·추천에 없는 것.
     * 기본 목록에 있는 태그는 아래 '기본 활동 태그' 줄에만 보인다(같은 태그가 두 번 보이지 않게).
     */
    static List<String> aiRow(List<String> suggested, List<String> selected, List<String> defaults) {
        Set<String> row = new LinkedHashSet<>();
        for (String tag : suggested) {
            if (tag != null && !defaults.contains(tag)) row.add(tag);
        }
        for (String tag : selected) {
            if (tag != null && !defaults.contains(tag)) row.add(tag);
        }
        return new ArrayList<>(row);
    }
}
