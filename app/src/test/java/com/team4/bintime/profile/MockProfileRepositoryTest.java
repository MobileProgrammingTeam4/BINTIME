package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MockProfileRepositoryTest {

    private MockProfileRepository repository;

    @Before
    public void setUp() {
        repository = new MockProfileRepository();
    }

    @Test
    public void defaultProfile_containsExpectedValues() {
        AtomicReference<UserProfile> profileRef = new AtomicReference<>();
        repository.getProfile("u1", new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile result) {
                profileRef.set(result);
            }

            @Override
            public void onError(String message) {}
        });

        UserProfile profile = profileRef.get();
        assertNotNull(profile);
        assertEquals("김지민", profile.getNickname());
        assertEquals("컴퓨터공학과", profile.getDepartment());
        assertEquals("3학년", profile.getGrade());
        assertEquals("여성", profile.getGender());
        assertEquals("컴퓨터공학과 3학년 · 여성", profile.getFormattedSummary());
        assertTrue(profile.getInterestTags().contains("스터디"));
    }

    @Test
    public void saveProfile_updatesDataCorrectly() {
        UserProfile newProfile = new UserProfile();
        newProfile.setNickname("홍길동");
        newProfile.setDepartment("전자공학과");
        newProfile.setGrade("2학년");
        newProfile.setGender("남성");
        newProfile.setBio("새로운 자기소개입니다.");
        newProfile.setInterestTags(Arrays.asList("코딩스터디", "밥"));

        repository.saveProfile(newProfile, null);

        AtomicReference<UserProfile> profileRef = new AtomicReference<>();
        repository.getProfile("u1", new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile result) {
                profileRef.set(result);
            }

            @Override
            public void onError(String message) {}
        });

        UserProfile saved = profileRef.get();
        assertEquals("홍길동", saved.getNickname());
        assertEquals("전자공학과", saved.getDepartment());
        assertEquals("2학년", saved.getGrade());
        assertEquals("남성", saved.getGender());
        assertEquals("새로운 자기소개입니다.", saved.getBio());
        assertEquals("전자공학과 2학년 · 남성", saved.getFormattedSummary());
        assertEquals(2, saved.getInterestTags().size());
        assertTrue(saved.getInterestTags().contains("코딩스터디"));
    }

    @Test
    public void generateAiBio_success() {
        AtomicReference<String> bioRef = new AtomicReference<>();
        repository.generateAiBio("시간표 데이터", new ProfileRepository.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                bioRef.set(result);
            }

            @Override
            public void onError(String message) {}
        });

        assertNotNull(bioRef.get());
        assertTrue(bioRef.get().length() > 0);
    }

    @Test
    public void tagsList_containsExpectedOptions() {
        List<String> aiTags = repository.getAiRecommendedTags();
        assertTrue(aiTags.contains("코딩스터디"));
        assertTrue(aiTags.contains("길산책"));

        List<String> defaultTags = repository.getDefaultTags();
        assertTrue(defaultTags.contains("스터디"));
        assertTrue(defaultTags.contains("카페"));
        assertTrue(defaultTags.contains("산책"));
    }
}
