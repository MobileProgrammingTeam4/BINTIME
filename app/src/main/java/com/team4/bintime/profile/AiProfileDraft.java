package com.team4.bintime.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 자기소개 초안과 추천 태그 (BT-029). 사용자가 수정·선택한 뒤에야 저장되는 초안이다.
 * 태그는 BT-012 #3 결정대로 `#` 없이 담는다(화면에서만 붙인다).
 */
public final class AiProfileDraft {

    public final String bio;
    public final List<String> suggestedTags;

    public AiProfileDraft(String bio, List<String> suggestedTags) {
        this.bio = bio;
        this.suggestedTags = Collections.unmodifiableList(new ArrayList<>(suggestedTags));
    }
}
