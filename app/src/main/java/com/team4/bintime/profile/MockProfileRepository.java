package com.team4.bintime.profile;

import com.team4.bintime.R;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

/**
 * Sprint 1 목업용 가상 프로필 저장소.
 * Figma 41:10218, 102:2684, 41:24577 화면 데이터 제공.
 */
public class MockProfileRepository implements ProfileRepository, Serializable {

    private UserProfile currentProfile;

    public MockProfileRepository() {
        currentProfile = new UserProfile();
        currentProfile.setNickname("김지민");
        currentProfile.setDepartment("컴퓨터공학과");
        currentProfile.setGrade("3학년");
        currentProfile.setGender("여성");
        currentProfile.setBio("같이 공부할 친구 찾아요!\n(스터디, 산책, 카페 다 좋아해요 😊)");
        currentProfile.setPhotoResId(R.drawable.img_profile_jimin);
        currentProfile.setInterestTags(Arrays.asList("스터디", "카페", "산책"));
    }

    @Override
    public void getProfile(String uid, Callback<UserProfile> callback) {
        if (callback != null) {
            callback.onSuccess(currentProfile);
        }
    }

    @Override
    public void saveProfile(UserProfile profile, Callback<Void> callback) {
        if (profile != null) {
            this.currentProfile = profile;
        }
        if (callback != null) {
            callback.onSuccess(null);
        }
    }

    @Override
    public void generateAiBio(String timetableContext, Callback<String> callback) {
        String generated = "같이 공부할 친구 찾아요!\n(스터디, 산책, 카페 다 좋아해요 😊)";
        if (callback != null) {
            callback.onSuccess(generated);
        }
    }

    @Override
    public List<String> getAiRecommendedTags() {
        return Arrays.asList("코딩스터디", "교구구매·꿀아이템", "밥먹기·근처", "길산책");
    }

    @Override
    public List<String> getDefaultTags() {
        return Arrays.asList("스터디", "카페", "산책", "밥", "전공스터디");
    }
}
