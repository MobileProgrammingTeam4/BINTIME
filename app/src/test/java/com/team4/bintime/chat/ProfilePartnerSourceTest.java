package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.profile.ProfileRepository;
import com.team4.bintime.profile.UserProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 상대 공개 프로필 연결 (BT-034, #91 getPublicProfile). */
public class ProfilePartnerSourceTest {

    private final List<ProfileRepository.Callback<UserProfile>> pending = new ArrayList<>();
    private final List<String> requested = new ArrayList<>();
    private final ProfilePartnerSource source = new ProfilePartnerSource((uid, cb) -> {
        requested.add(uid);
        pending.add(cb);
    }, "상대");
    private final List<Object> events = new ArrayList<>();
    private final ChatRepository.Listener<PartnerInfo> listener = new ChatRepository.Listener<PartnerInfo>() {
        @Override public void onData(PartnerInfo data) { events.add(data == null ? "null" : data); }
        @Override public void onError() { events.add("error"); }
    };

    private static UserProfile profile(String nickname, String photoPath) {
        UserProfile p = UserProfile.empty();
        p.setUid("u2");
        p.setNickname(nickname);
        p.setDepartment("소프트웨어학과");
        p.setGrade("3학년");
        p.setPhotoPath(photoPath);
        p.setEmail("secret@example.com");
        return p;
    }

    @Test
    public void success_mapsPublicFields() {
        source.observe("u2", listener);
        assertEquals(Collections.singletonList("u2"), requested);
        pending.get(0).onSuccess(profile("지민", "profileImages/u2/profile.jpg"));
        PartnerInfo info = (PartnerInfo) events.get(0);
        assertEquals("u2", info.uid);
        assertEquals("지민", info.name);
        assertEquals("소프트웨어학과 3학년", info.info);
        assertTrue(info.hasPhoto);
        assertEquals("profileImages/u2/profile.jpg", info.photoPath);
    }

    @Test
    public void blankNicknameAndNoPhoto_useFallback() {
        PartnerInfo info = ProfilePartnerSource.toPartner("u2", profile("  ", " "), "상대");
        assertEquals("상대", info.name);
        assertFalse(info.hasPhoto);
        assertNull(info.photoPath);
    }

    @Test
    public void missingProfile_deliversNull() {
        source.observe("u2", listener);
        pending.get(0).onSuccess(null);
        assertEquals(Collections.singletonList("null"), events);
    }

    @Test
    public void error_deliversOnError() {
        source.observe("u2", listener);
        pending.get(0).onError("권한 없음");
        assertEquals(Collections.singletonList("error"), events);
    }

    @Test
    public void lateResultAfterRemove_isIgnored() {
        ChatRepository.Registration registration = source.observe("u2", listener);
        registration.remove();
        pending.get(0).onSuccess(profile("지민", null));
        pending.get(0).onError("늦은 오류");
        assertTrue(events.isEmpty());
    }

    @Test
    public void duplicateCallback_deliveredOnce() {
        source.observe("u2", listener);
        pending.get(0).onSuccess(profile("지민", null));
        pending.get(0).onError("중복");
        pending.get(0).onSuccess(profile("다른 이름", null));
        assertEquals(1, events.size());
    }

    @Test
    public void synchronousCallback_isDelivered() {
        ProfilePartnerSource sync = new ProfilePartnerSource((uid, cb) -> cb.onSuccess(profile("지민", null)), "상대");
        sync.observe("u2", listener);
        assertEquals("지민", ((PartnerInfo) events.get(0)).name);
    }

    private static UserChatRoomEntry entry() {
        return new UserChatRoomEntry("room", "u2", "안녕", 100L, 0, ChatRoom.Status.ACTIVE, false);
    }

    @Test
    public void mapperPassesPhotoPathToRoom() {
        ChatRoom room = ChatRtdbMapper.room("me", entry(), null,
                new PartnerInfo("u2", "지민", null, true, "profileImages/u2/profile.jpg"), "상대");
        assertEquals("profileImages/u2/profile.jpg", room.otherPhotoPath);
        // 다른 사람의 프로필이면 쓰지 않는다
        ChatRoom other = ChatRtdbMapper.room("me", entry(), null,
                new PartnerInfo("u3", "다른 사람", null, true, "profileImages/u3/profile.jpg"), "상대");
        assertNull(other.otherPhotoPath);
        assertEquals("상대", other.otherName);
    }
}
