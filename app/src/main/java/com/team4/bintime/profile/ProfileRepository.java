package com.team4.bintime.profile;

import java.util.List;

/**
 * 프로필 데이터 CRUD 및 AI 생성 계약 인터페이스 (BT-004).
 */
public interface ProfileRepository {

    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    void getProfile(String uid, Callback<UserProfile> callback);

    void saveProfile(UserProfile profile, Callback<Void> callback);

    void generateAiBio(String timetableContext, Callback<String> callback);

    List<String> getAiRecommendedTags();

    List<String> getDefaultTags();
}
