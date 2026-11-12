package com.team4.bintime.profile;

import android.graphics.Bitmap;

import java.util.List;

/**
 * 프로필 데이터 CRUD 및 AI 생성 계약 인터페이스 (BT-004, BT-014).
 * 구현체: {@link MockProfileRepository}(google-services.json 없음), {@link FirebaseProfileRepository}.
 * 선택은 {@link ProfileRepositories#get}.
 */
public interface ProfileRepository {

    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    /** uid 의 프로필. 아직 만든 적이 없으면 onSuccess(null). */
    void getProfile(String uid, Callback<UserProfile> callback);

    /** 필수값 검증 후 저장. 로그인한 본인 UID 의 프로필만 저장할 수 있다. */
    void saveProfile(UserProfile profile, Callback<Void> callback);

    /** 프로필 생성을 마친 계정인지. 앱 재시작·재로그인 시 가입 단계 복귀 여부를 정한다. */
    void hasCompletedProfile(String uid, Callback<Boolean> callback);

    /** 압축된 JPEG 를 업로드하고 저장 경로(예: profileImages/{uid}/profile.jpg)를 돌려준다. */
    void uploadProfilePhoto(String uid, byte[] jpegBytes, Callback<String> callback);

    /** {@link UserProfile#getPhotoPath()} 경로의 사진을 불러온다. */
    void loadProfilePhoto(String photoPath, Callback<Bitmap> callback);

    void generateAiBio(String timetableContext, Callback<String> callback);

    List<String> getAiRecommendedTags();

    List<String> getDefaultTags();
}
