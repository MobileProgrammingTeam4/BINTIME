package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BT-014: 프로필 ↔ Firestore 문서 변환. */
public class ProfileDocumentsTest {

    private static UserProfile sample() {
        UserProfile p = UserProfile.empty();
        p.setUid("uidA");
        p.setEmail("a@kookmin.ac.kr");
        p.setNickname("지민");
        p.setDepartment("소프트웨어학부");
        p.setGrade("3학년");
        p.setGender("여성");
        p.setBio("안녕");
        p.setInterestTags(Arrays.asList("카페", "산책"));
        p.setPhotoPath(ProfileDocuments.photoPath("uidA"));
        return p;
    }

    @Test
    public void photoPath_isPerUid() {
        assertEquals("profileImages/uidA/profile.jpg", ProfileDocuments.photoPath("uidA"));
    }

    @Test
    public void userDocument_roundTrips() {
        Map<String, Object> doc = ProfileDocuments.toUserDocument(sample());
        UserProfile back = ProfileDocuments.fromDocument("uidA", doc);

        assertEquals("uidA", back.getUid());
        assertEquals("a@kookmin.ac.kr", back.getEmail());
        assertEquals("지민", back.getNickname());
        assertEquals("소프트웨어학부", back.getDepartment());
        assertEquals("3학년", back.getGrade());
        assertEquals("여성", back.getGender());
        assertEquals("안녕", back.getBio());
        assertEquals(Arrays.asList("카페", "산책"), back.getInterestTags());
        assertEquals("profileImages/uidA/profile.jpg", back.getPhotoPath());
        assertTrue(ProfileDocuments.isCompleted(doc));
    }

    @Test
    public void publicDocument_excludesEmailAndCompletionFlag() {
        Map<String, Object> doc = ProfileDocuments.toPublicDocument(sample());
        assertFalse(doc.containsKey(ProfileDocuments.F_EMAIL));
        assertFalse(doc.containsKey(ProfileDocuments.F_PROFILE_COMPLETED));
        assertEquals("uidA", doc.get(ProfileDocuments.F_UID));
    }

    @Test
    public void fromPublicDocument_readsPublicFields() {
        UserProfile back = ProfileDocuments.fromPublicDocument("uidA",
                ProfileDocuments.toPublicDocument(sample()));
        assertEquals("uidA", back.getUid());
        assertNull(back.getEmail());
        assertEquals("지민", back.getNickname());
        assertEquals("소프트웨어학부", back.getDepartment());
        assertEquals(Arrays.asList("카페", "산책"), back.getInterestTags());
        assertEquals("profileImages/uidA/profile.jpg", back.getPhotoPath());
    }

    /** Rules 가 막지만, 공개 문서에 이메일이 섞여 있어도 앱 결과에는 넣지 않는다. */
    @Test
    public void fromPublicDocument_dropsEmailEvenIfPresent() {
        Map<String, Object> doc = ProfileDocuments.toUserDocument(sample());
        assertNull(ProfileDocuments.fromPublicDocument("uidA", doc).getEmail());
    }

    @Test
    public void toPublicProfile_isCopyWithoutEmail() {
        UserProfile original = sample();
        UserProfile pub = ProfileDocuments.toPublicProfile(original);
        assertNull(pub.getEmail());
        assertEquals("a@kookmin.ac.kr", original.getEmail());
        assertEquals("지민", pub.getNickname());
    }

    @Test
    public void fromDocument_missingFields_doNotInjectDemoValues() {
        UserProfile back = ProfileDocuments.fromDocument("uidB", new HashMap<>());
        assertEquals("uidB", back.getUid());
        assertNull(back.getEmail());
        assertEquals("", back.getNickname());
        assertEquals("", back.getDepartment());
        assertEquals(0, back.getPhotoResId());
        assertNull(back.getPhotoPath());
        assertTrue(back.getInterestTags().isEmpty());
    }

    @Test
    public void fromDocument_usesRequestedUid_notDocumentField() {
        Map<String, Object> doc = ProfileDocuments.toUserDocument(sample());
        assertEquals("uidC", ProfileDocuments.fromDocument("uidC", doc).getUid());
    }

    @Test
    public void fromDocument_ignoresWrongTypes() {
        Map<String, Object> doc = new HashMap<>();
        doc.put(ProfileDocuments.F_NICKNAME, 42);
        List<Object> tags = Arrays.asList("카페", 7, null);
        doc.put(ProfileDocuments.F_INTEREST_TAGS, tags);
        UserProfile back = ProfileDocuments.fromDocument("u", doc);
        assertEquals("", back.getNickname());
        assertEquals(Arrays.asList("카페"), back.getInterestTags());
    }

    @Test
    public void isCompleted_falseWithoutFlag() {
        assertFalse(ProfileDocuments.isCompleted(null));
        assertFalse(ProfileDocuments.isCompleted(new HashMap<>()));
    }

    @Test
    public void photoSampleSize_keepsLongSideAboveTarget() {
        assertEquals(1, ProfilePhotoCompressor.sampleSize(800, 600));
        assertEquals(2, ProfilePhotoCompressor.sampleSize(1024, 768));
        assertEquals(4, ProfilePhotoCompressor.sampleSize(3000, 4000));
    }
}
