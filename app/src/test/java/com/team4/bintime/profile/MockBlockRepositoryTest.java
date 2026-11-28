package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** BT-046 Mock 차단 저장소: 최근 순 목록, 멱등 차단·해제, 탈퇴한 상대 표시. */
public class MockBlockRepositoryTest {

    private MockProfileRepository profiles;
    private MockBlockRepository repository;

    @Before
    public void setUp() {
        profiles = new MockProfileRepository();
        repository = new MockBlockRepository(profiles);
        UserProfile other = UserProfile.empty();
        other.setUid("u1");
        other.setNickname("박준호");
        other.setDepartment("전자공학과");
        profiles.putPublicProfile(other);
    }

    private List<BlockedUser> list() {
        final Object[] result = {null};
        repository.getBlockedUsers(new BlockRepository.Callback<List<BlockedUser>>() {
            @Override public void onSuccess(List<BlockedUser> users) { result[0] = users; }
            @Override public void onError(String message) { }
        });
        assertNotNull(result[0]);
        @SuppressWarnings("unchecked") List<BlockedUser> users = (List<BlockedUser>) result[0];
        return users;
    }

    private final BlockRepository.Callback<Void> ignore = new BlockRepository.Callback<Void>() {
        @Override public void onSuccess(Void result) { }
        @Override public void onError(String message) { }
    };

    @Test
    public void emptyByDefault() {
        assertTrue(list().isEmpty());
    }

    @Test
    public void blockListsMostRecentFirstWithPublicProfile() {
        repository.block("u1", ignore);
        repository.block("gone", ignore); // 공개 프로필 없음(탈퇴 등)
        List<BlockedUser> users = list();
        assertEquals(2, users.size());
        assertEquals("gone", users.get(0).uid);
        assertNull(users.get(0).nickname()); // 화면에서 "알 수 없는 사용자"
        assertEquals("", users.get(0).summary());
        assertEquals("박준호", users.get(1).nickname());
        assertTrue(users.get(1).summary().startsWith("전자공학과"));
    }

    @Test
    public void blockAndUnblockAreIdempotent() {
        repository.block("u1", ignore);
        repository.block("u1", ignore);
        assertEquals(1, list().size());
        repository.unblock("u1", ignore);
        repository.unblock("u1", ignore);
        assertTrue(list().isEmpty());
        assertFalse(repository.isBlocked("u1"));
    }

    @Test
    public void blankTargetFails() {
        final String[] error = {null};
        repository.block("", new BlockRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) { }
            @Override public void onError(String message) { error[0] = message; }
        });
        assertNotNull(error[0]);
        assertTrue(list().isEmpty());
    }
}
