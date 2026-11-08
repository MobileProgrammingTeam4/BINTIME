package com.team4.bintime.profile;

/**
 * 프로필 저장 전 필수 입력값 검증 (BT-004 / Issue #4).
 * 화면(ProfileSetupActivity)과 저장소(MockProfileRepository)가 같은 규칙을 사용한다.
 */
public final class ProfileValidator {

    public static final int NICKNAME_MAX_LENGTH = 10;
    public static final int BIO_MAX_LENGTH = 150;

    public enum Error {
        NICKNAME_REQUIRED("닉네임을 입력해 주세요"),
        NICKNAME_TOO_LONG("닉네임은 " + NICKNAME_MAX_LENGTH + "자 이하로 입력해 주세요"),
        DEPARTMENT_REQUIRED("학과를 입력해 주세요"),
        BIO_TOO_LONG("자기소개는 " + BIO_MAX_LENGTH + "자 이하로 입력해 주세요");

        private final String message;

        Error(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    private ProfileValidator() {}

    /** 닉네임 검증. 통과하면 null. */
    public static Error validateNickname(String nickname) {
        String value = nickname != null ? nickname.trim() : "";
        if (value.isEmpty()) return Error.NICKNAME_REQUIRED;
        if (value.length() > NICKNAME_MAX_LENGTH) return Error.NICKNAME_TOO_LONG;
        return null;
    }

    /** 학과 검증. 통과하면 null. */
    public static Error validateDepartment(String department) {
        String value = department != null ? department.trim() : "";
        return value.isEmpty() ? Error.DEPARTMENT_REQUIRED : null;
    }

    /** 자기소개 검증 (선택 입력, 길이만 제한). 통과하면 null. */
    public static Error validateBio(String bio) {
        if (bio != null && bio.trim().length() > BIO_MAX_LENGTH) return Error.BIO_TOO_LONG;
        return null;
    }

    /** 프로필 전체 검증. 첫 번째 오류를 반환하고, 모두 통과하면 null. */
    public static Error validate(UserProfile profile) {
        if (profile == null) return Error.NICKNAME_REQUIRED;
        Error error = validateNickname(profile.getNickname());
        if (error != null) return error;
        error = validateDepartment(profile.getDepartment());
        if (error != null) return error;
        return validateBio(profile.getBio());
    }
}
