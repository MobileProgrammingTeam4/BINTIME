package com.team4.bintime.profile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 차단 목록 한 줄(BT-046, Figma 117:1906). 이름·요약은 상대 공개 프로필(publicProfiles)에서 읽는다.
 * 상대가 탈퇴해 공개 프로필이 없으면 profile 이 null 이고 화면은 대체 이름을 쓴다.
 */
public final class BlockedUser {

    @NonNull public final String uid;
    @Nullable public final UserProfile profile;

    public BlockedUser(@NonNull String uid, @Nullable UserProfile profile) {
        this.uid = uid;
        this.profile = profile;
    }

    /** 표시 이름. 공개 프로필이 없거나 닉네임이 비면 null(화면에서 "알 수 없는 사용자"). */
    @Nullable
    public String nickname() {
        String name = profile != null ? profile.getNickname() : null;
        return name == null || name.trim().isEmpty() ? null : name;
    }

    /** "전자공학과 3학년 · 남성". 없으면 빈 문자열. */
    @NonNull
    public String summary() {
        return profile != null ? profile.getFormattedSummary() : "";
    }
}
