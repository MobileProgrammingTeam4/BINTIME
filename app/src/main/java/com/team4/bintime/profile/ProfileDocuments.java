package com.team4.bintime.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로필 ↔ Firestore 문서 변환 (BT-014). Android/Firebase 의존성이 없어 단위 테스트로 검증한다.
 *
 * users/{uid}          본인만 읽고 쓰는 전체 프로필 (이메일 포함)
 * publicProfiles/{uid} 로그인 사용자가 읽는 공개 프로필 (이메일 등 개인정보 제외)
 * Storage profileImages/{uid}/profile.jpg
 *
 * UserProfile 기본 생성자는 시연 값을 채우므로 toObject() 대신 이 변환을 쓴다.
 */
public final class ProfileDocuments {

    public static final String USERS = "users";
    public static final String PUBLIC_PROFILES = "publicProfiles";

    public static final String F_UID = "uid";
    public static final String F_EMAIL = "email";
    public static final String F_NICKNAME = "nickname";
    public static final String F_DEPARTMENT = "department";
    public static final String F_GRADE = "grade";
    public static final String F_GENDER = "gender";
    public static final String F_BIO = "bio";
    public static final String F_INTEREST_TAGS = "interestTags";
    public static final String F_PHOTO_PATH = "photoPath";
    public static final String F_PROFILE_COMPLETED = "profileCompleted";
    public static final String F_UPDATED_AT = "updatedAt";

    private ProfileDocuments() {}

    public static String photoPath(String uid) {
        return "profileImages/" + uid + "/profile.jpg";
    }

    /** users/{uid} 문서. 타임스탬프는 저장소가 서버 시간으로 채운다. */
    public static Map<String, Object> toUserDocument(UserProfile profile) {
        Map<String, Object> doc = toPublicDocument(profile);
        doc.put(F_EMAIL, profile.getEmail());
        doc.put(F_PROFILE_COMPLETED, true);
        return doc;
    }

    /** publicProfiles/{uid} 문서. 이메일은 넣지 않는다. */
    public static Map<String, Object> toPublicDocument(UserProfile profile) {
        Map<String, Object> doc = new HashMap<>();
        doc.put(F_UID, profile.getUid());
        doc.put(F_NICKNAME, profile.getNickname());
        doc.put(F_DEPARTMENT, profile.getDepartment());
        doc.put(F_GRADE, profile.getGrade());
        doc.put(F_GENDER, profile.getGender());
        doc.put(F_BIO, profile.getBio() != null ? profile.getBio() : "");
        doc.put(F_INTEREST_TAGS, profile.getInterestTags() != null
                ? new ArrayList<>(profile.getInterestTags()) : new ArrayList<String>());
        doc.put(F_PHOTO_PATH, profile.getPhotoPath());
        return doc;
    }

    /** users/{uid} 문서 → 프로필. 빠진 필드는 빈 값으로 둔다 (시연 값 주입 방지). */
    public static UserProfile fromDocument(String uid, Map<String, Object> data) {
        UserProfile profile = UserProfile.empty();
        profile.setUid(uid);
        if (data == null) return profile;
        profile.setEmail(string(data.get(F_EMAIL), null));
        profile.setNickname(string(data.get(F_NICKNAME), ""));
        profile.setDepartment(string(data.get(F_DEPARTMENT), ""));
        profile.setGrade(string(data.get(F_GRADE), ""));
        profile.setGender(string(data.get(F_GENDER), ""));
        profile.setBio(string(data.get(F_BIO), ""));
        profile.setPhotoPath(string(data.get(F_PHOTO_PATH), null));
        profile.setInterestTags(stringList(data.get(F_INTEREST_TAGS)));
        return profile;
    }

    /**
     * publicProfiles/{uid} 문서 → 공개 프로필. 문서에 이메일이 섞여 있어도 결과에 넣지 않는다
     * (Rules 가 막지만 앱에서도 한 번 더 걸러 다른 사용자 이메일이 화면에 나가지 않게 한다).
     */
    public static UserProfile fromPublicDocument(String uid, Map<String, Object> data) {
        UserProfile profile = fromDocument(uid, data);
        profile.setEmail(null);
        return profile;
    }

    /** 공개용 복사본: 이메일을 지운다. Mock 저장소가 쓴다. */
    public static UserProfile toPublicProfile(UserProfile profile) {
        UserProfile copy = profile.copy();
        copy.setEmail(null);
        return copy;
    }

    public static boolean isCompleted(Map<String, Object> data) {
        return data != null && Boolean.TRUE.equals(data.get(F_PROFILE_COMPLETED));
    }

    private static String string(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof String) result.add((String) item);
            }
        }
        return result;
    }
}
