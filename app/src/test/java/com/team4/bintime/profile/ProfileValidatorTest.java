package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ProfileValidatorTest {

    @Test
    public void nickname_requiredAndLengthLimited() {
        assertEquals(ProfileValidator.Error.NICKNAME_REQUIRED, ProfileValidator.validateNickname(null));
        assertEquals(ProfileValidator.Error.NICKNAME_REQUIRED, ProfileValidator.validateNickname(""));
        assertEquals(ProfileValidator.Error.NICKNAME_REQUIRED, ProfileValidator.validateNickname("   "));
        assertNull(ProfileValidator.validateNickname("김"));
        assertNull(ProfileValidator.validateNickname("가나다라마바사아자")); // 9자
        assertNull(ProfileValidator.validateNickname("가나다라마바사아자차")); // 10자
        assertEquals(ProfileValidator.Error.NICKNAME_TOO_LONG,
                ProfileValidator.validateNickname("가나다라마바사아자차카")); // 11자
    }

    @Test
    public void department_required() {
        assertEquals(ProfileValidator.Error.DEPARTMENT_REQUIRED, ProfileValidator.validateDepartment(null));
        assertEquals(ProfileValidator.Error.DEPARTMENT_REQUIRED, ProfileValidator.validateDepartment(""));
        assertEquals(ProfileValidator.Error.DEPARTMENT_REQUIRED, ProfileValidator.validateDepartment(" \t "));
        assertNull(ProfileValidator.validateDepartment("컴퓨터공학과"));
    }

    @Test
    public void bio_optionalButLengthLimited() {
        assertNull(ProfileValidator.validateBio(null));
        assertNull(ProfileValidator.validateBio(""));
        assertNull(ProfileValidator.validateBio(repeat(ProfileValidator.BIO_MAX_LENGTH)));
        assertEquals(ProfileValidator.Error.BIO_TOO_LONG,
                ProfileValidator.validateBio(repeat(ProfileValidator.BIO_MAX_LENGTH + 1)));
    }

    @Test
    public void validate_returnsFirstErrorInFieldOrder() {
        UserProfile profile = UserProfile.empty();
        assertEquals(ProfileValidator.Error.NICKNAME_REQUIRED, ProfileValidator.validate(profile));

        profile.setNickname("지민");
        assertEquals(ProfileValidator.Error.DEPARTMENT_REQUIRED, ProfileValidator.validate(profile));

        profile.setDepartment("컴퓨터공학과");
        assertNull(ProfileValidator.validate(profile));

        assertEquals(ProfileValidator.Error.NICKNAME_REQUIRED, ProfileValidator.validate(null));
    }

    private static String repeat(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append('가');
        return sb.toString();
    }
}
