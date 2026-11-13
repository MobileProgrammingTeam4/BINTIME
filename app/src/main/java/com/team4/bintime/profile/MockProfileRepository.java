package com.team4.bintime.profile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.team4.bintime.R;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 1 목업용 가상 프로필 저장소.
 * Figma 41:10218, 102:2684, 41:24577 화면 데이터 제공.
 *
 * 프로필 설정 화면과 마이페이지가 같은 데이터를 보도록 {@link #shared()} 인스턴스를 사용한다.
 * 메모리에만 저장하므로 앱 프로세스가 종료되면 기본 시연 값으로 돌아간다.
 * google-services.json 이 있으면 {@link FirebaseProfileRepository}(Firestore `users/{uid}`·`publicProfiles/{uid}`,
 * Storage `profileImages/{uid}`)가 대신 쓰인다 (BT-014, {@link ProfileRepositories}).
 */
public class MockProfileRepository implements ProfileRepository {

    private static MockProfileRepository shared;

    /** 앱 전역에서 공유하는 Mock 프로필 저장소. */
    public static synchronized MockProfileRepository shared() {
        if (shared == null) shared = new MockProfileRepository();
        return shared;
    }

    /** 단위 테스트 간 상태 격리용. */
    static synchronized void resetShared() {
        shared = null;
    }

    private UserProfile currentProfile;

    /** 시연용 업로드 사진. 경로 → JPEG 바이트. */
    private final Map<String, byte[]> photos = new HashMap<>();

    /** 다른 사용자 공개 프로필(uid → 이메일 없는 복사본). publicProfiles/{uid} 를 흉내 낸다. */
    private final Map<String, UserProfile> publicProfiles = new HashMap<>();

    public boolean hasCompletedProfile(String uid) {
        return uid != null && uid.equals(currentProfile.getUid());
    }

    @Override
    public void hasCompletedProfile(String uid, Callback<Boolean> callback) {
        if (callback != null) callback.onSuccess(hasCompletedProfile(uid));
    }

    @Override
    public void uploadProfilePhoto(String uid, byte[] jpegBytes, Callback<String> callback) {
        if (uid == null || jpegBytes == null || jpegBytes.length == 0) {
            if (callback != null) callback.onError("사진을 불러오지 못했어요.");
            return;
        }
        String path = ProfileDocuments.photoPath(uid);
        photos.put(path, jpegBytes.clone());
        if (callback != null) callback.onSuccess(path);
    }

    @Override
    public void loadProfilePhoto(String photoPath, Callback<Bitmap> callback) {
        byte[] bytes = photoPath != null ? photos.get(photoPath) : null;
        if (bytes == null) {
            if (callback != null) callback.onError("사진을 불러오지 못했어요.");
            return;
        }
        if (callback != null) callback.onSuccess(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
    }

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

    /** 저장된 프로필의 복사본을 돌려준다. 화면에서 편집해도 저장 전에는 저장소 값이 바뀌지 않는다. */
    @Override
    public void getProfile(String uid, Callback<UserProfile> callback) {
        if (callback != null) {
            callback.onSuccess(currentProfile.copy());
        }
    }

    /** 필수값(닉네임·학과) 검증을 통과한 경우에만 저장한다. */
    @Override
    public void saveProfile(UserProfile profile, Callback<Void> callback) {
        ProfileValidator.Error error = ProfileValidator.validate(profile);
        if (error != null) {
            if (callback != null) callback.onError(error.getMessage());
            return;
        }

        UserProfile toSave = profile.copy();
        toSave.setNickname(toSave.getNickname().trim());
        toSave.setDepartment(toSave.getDepartment().trim());
        if (toSave.getBio() != null) toSave.setBio(toSave.getBio().trim());
        this.currentProfile = toSave;
        // Firebase 저장소처럼 공개 프로필도 함께 갱신한다(uid 가 있는 경우만).
        if (toSave.getUid() != null) {
            publicProfiles.put(toSave.getUid(), ProfileDocuments.toPublicProfile(toSave));
        }

        if (callback != null) {
            callback.onSuccess(null);
        }
    }

    /** 저장되거나 {@link #putPublicProfile} 로 등록된 공개 프로필의 복사본. 없으면 null, 이메일은 항상 null. */
    @Override
    public void getPublicProfile(String uid, Callback<UserProfile> callback) {
        if (uid == null || uid.isEmpty()) {
            if (callback != null) callback.onError("사용자 정보를 찾을 수 없어요.");
            return;
        }
        UserProfile found = publicProfiles.get(uid);
        if (callback != null) callback.onSuccess(found != null ? found.copy() : null);
    }

    /**
     * Mock 전용: 다른 사용자의 공개 프로필을 등록한다(시연·테스트용 후보, 요청 상대 등).
     * uid 가 없으면 무시한다. 저장 시 이메일은 지운다.
     */
    public void putPublicProfile(UserProfile profile) {
        if (profile == null || profile.getUid() == null) return;
        publicProfiles.put(profile.getUid(), ProfileDocuments.toPublicProfile(profile));
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
