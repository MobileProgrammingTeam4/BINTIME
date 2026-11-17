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

    /** uid 의 프로필. 아직 만든 적이 없으면 onSuccess(null). 본인 전용(users/{uid})이라 다른 사용자에게는 쓰지 않는다. */
    void getProfile(String uid, Callback<UserProfile> callback);

    /**
     * 다른 사용자에게 보여 줄 공개 프로필(publicProfiles/{uid}). 매칭 카드·요청 카드·채팅 상대 표시용.
     * 결과의 이메일은 항상 null 이다. 공개 프로필이 없으면(가입 미완료 등) onSuccess(null).
     * 문서에 빠진 문자열 필드는 null 이 아니라 빈 문자열("")로 채운다(닉네임이 비면 화면에서 대체 이름을 쓴다).
     * 사진은 {@link UserProfile#getPhotoPath()} 를 {@link #loadProfilePhoto} 로 불러온다.
     * <p>
     * 콜백 계약: onSuccess/onError 중 하나가 <b>정확히 한 번</b> 호출된다. Firebase 구현의 조회 결과는
     * 메인 스레드로 온다. uid 가 비어 있거나 로그인 전이면, 그리고 Mock 구현은 호출한 스레드에서 바로 콜백한다.
     * 화면에서 메인 스레드로 호출하면 결과도 항상 메인 스레드다. 취소 API 는 없으므로 화면이 닫힌 뒤 온 결과는
     * 호출한 쪽에서 무시한다. 한 번 읽기(get)라 상대가 프로필을 바꿔도 다시 조회해야 반영된다.
     * <p>
     * Mock: 저장한 프로필과 {@link MockProfileRepository#putPublicProfile} 로 등록한 사용자만 조회된다.
     * Mock 후보·요청 상대를 보여 주려면 사용하는 쪽에서 먼저 등록한다(등록하지 않으면 null).
     */
    void getPublicProfile(String uid, Callback<UserProfile> callback);

    /** 필수값 검증 후 저장. 로그인한 본인 UID 의 프로필만 저장할 수 있다. */
    void saveProfile(UserProfile profile, Callback<Void> callback);

    /** 프로필 생성을 마친 계정인지. 앱 재시작·재로그인 시 가입 단계 복귀 여부를 정한다. */
    void hasCompletedProfile(String uid, Callback<Boolean> callback);

    /** 압축된 JPEG 를 업로드하고 저장 경로(예: profileImages/{uid}/profile.jpg)를 돌려준다. */
    void uploadProfilePhoto(String uid, byte[] jpegBytes, Callback<String> callback);

    /** {@link UserProfile#getPhotoPath()} 경로의 사진을 불러온다. */
    void loadProfilePhoto(String photoPath, Callback<Bitmap> callback);

    /**
     * AI 자기소개 초안과 추천 태그(BT-029). Firebase 는 generateProfile Callable(asia-northeast3), Mock 은 고정 규칙.
     * 결과는 초안일 뿐 저장하지 않는다. 실패하면 onError 에 사용자 문구({@link AiProfileMapper#errorMessage})를 준다.
     * 콜백은 정확히 한 번, 메인 스레드로 온다.
     */
    void generateAiProfile(AiProfileRequest request, Callback<AiProfileDraft> callback);

    /** 기본 활동 태그(# 없이). */
    List<String> getDefaultTags();
}
