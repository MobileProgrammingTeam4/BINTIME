package com.team4.bintime.chat;

import androidx.annotation.Nullable;

/** 채팅 상대의 공개 프로필 요약. 출처는 publicProfiles/{uid}(B, BT-014 #79·#91). */
public final class PartnerInfo {
    public final String uid, name;
    /** 학과·학년·성별 요약. 없으면 null */
    @Nullable public final String info;
    public final boolean hasPhoto;
    /** Storage 사진 경로. 없으면 null(Mock 은 경로 없이 기본 이미지를 쓴다). */
    @Nullable public final String photoPath;

    public PartnerInfo(String uid, String name, @Nullable String info, boolean hasPhoto) {
        this(uid, name, info, hasPhoto, null);
    }

    public PartnerInfo(String uid, String name, @Nullable String info, boolean hasPhoto, @Nullable String photoPath) {
        this.uid = uid;
        this.name = name;
        this.info = info;
        this.hasPhoto = hasPhoto;
        this.photoPath = photoPath;
    }
}
