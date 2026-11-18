package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MockProfileRepositoryTest {

    /** 콜백 결과를 기록하는 테스트용 콜백. */
    private static final class Recorder<T> implements ProfileRepository.Callback<T> {
        boolean success;
        String errorMessage;

        @Override public void onSuccess(T result) { success = true; }
        @Override public void onError(String message) { errorMessage = message; }
    }

    private MockProfileRepository repository;

    @Before
    public void setUp() {
        MockProfileRepository.resetShared();
        repository = new MockProfileRepository();
    }

    @After
    public void tearDown() {
        MockProfileRepository.resetShared();
    }

    private static UserProfile load(ProfileRepository repo) {
        AtomicReference<UserProfile> ref = new AtomicReference<>();
        repo.getProfile("u1", new ProfileRepository.Callback<UserProfile>() {
            @Override public void onSuccess(UserProfile result) { ref.set(result); }
            @Override public void onError(String message) {}
        });
        return ref.get();
    }

    private static Recorder<Void> save(ProfileRepository repo, UserProfile profile) {
        Recorder<Void> recorder = new Recorder<>();
        repo.saveProfile(profile, recorder);
        return recorder;
    }

    @Test
    public void defaultProfile_containsExpectedValues() {
        UserProfile profile = load(repository);
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

        assertTrue(save(repository, newProfile).success);

        UserProfile saved = load(repository);
        assertEquals("홍길동", saved.getNickname());
        assertEquals("전자공학과", saved.getDepartment());
        assertEquals("2학년", saved.getGrade());
        assertEquals("남성", saved.getGender());
        assertEquals("새로운 자기소개입니다.", saved.getBio());
        assertEquals("전자공학과 2학년 · 남성", saved.getFormattedSummary());
        assertEquals(2, saved.getInterestTags().size());
        assertTrue(saved.getInterestTags().contains("코딩스터디"));
    }

    // ---- 필수값 누락 시 저장 차단 ----

    @Test
    public void saveProfile_blocked_whenNicknameEmpty() {
        UserProfile draft = load(repository);
        draft.setNickname("");

        Recorder<Void> result = save(repository, draft);

        assertFalse(result.success);
        assertEquals(ProfileValidator.Error.NICKNAME_REQUIRED.getMessage(), result.errorMessage);
        assertEquals("김지민", load(repository).getNickname());
    }

    @Test
    public void saveProfile_blocked_whenNicknameBlankSpaces() {
        UserProfile draft = load(repository);
        draft.setNickname("   ");

        assertFalse(save(repository, draft).success);
        assertEquals("김지민", load(repository).getNickname());
    }

    @Test
    public void saveProfile_blocked_whenDepartmentEmpty() {
        UserProfile draft = load(repository);
        draft.setNickname("새닉네임");
        draft.setDepartment("  ");

        Recorder<Void> result = save(repository, draft);

        assertFalse(result.success);
        assertEquals(ProfileValidator.Error.DEPARTMENT_REQUIRED.getMessage(), result.errorMessage);
        UserProfile stored = load(repository);
        assertEquals("김지민", stored.getNickname());
        assertEquals("컴퓨터공학과", stored.getDepartment());
    }

    @Test
    public void saveProfile_blocked_whenNullFieldsOrNullProfile() {
        UserProfile draft = UserProfile.empty();
        draft.setNickname(null);
        draft.setDepartment(null);

        assertFalse(save(repository, draft).success);
        assertFalse(save(repository, null).success);
        assertEquals("김지민", load(repository).getNickname());
    }

    @Test
    public void saveProfile_trimsValidInput() {
        UserProfile draft = load(repository);
        draft.setNickname("  박서준 ");
        draft.setDepartment(" 경영학과  ");

        assertTrue(save(repository, draft).success);

        UserProfile stored = load(repository);
        assertEquals("박서준", stored.getNickname());
        assertEquals("경영학과", stored.getDepartment());
    }

    // ---- 프로필 수정 데이터 공유 (설정 화면 ↔ 마이페이지) ----

    @Test
    public void sharedInstance_isSameForSetupAndMyPage() {
        assertSame(MockProfileRepository.shared(), MockProfileRepository.shared());
    }

    @Test
    public void editProfile_isReflectedOnMyPage() {
        // 프로필 수정 화면과 마이페이지가 각자 shared() 를 얻는 상황을 재현
        ProfileRepository setupScreenRepo = MockProfileRepository.shared();
        ProfileRepository myPageRepo = MockProfileRepository.shared();

        UserProfile draft = load(setupScreenRepo);
        draft.setNickname("이수민");
        draft.setDepartment("디자인학과");
        draft.setGrade("4학년");
        draft.setBio("카페에서 같이 과제해요");
        draft.setInterestTags(Arrays.asList("카페", "전공스터디"));
        assertTrue(save(setupScreenRepo, draft).success);

        UserProfile shown = load(myPageRepo);
        assertEquals("이수민", shown.getNickname());
        assertEquals("디자인학과", shown.getDepartment());
        assertEquals("4학년", shown.getGrade());
        assertEquals("카페에서 같이 과제해요", shown.getBio());
        assertEquals(Arrays.asList("카페", "전공스터디"), shown.getInterestTags());
        assertEquals("디자인학과 4학년 · 여성", shown.getFormattedSummary());
    }

    @Test
    public void reenterEditScreen_showsSavedValues() {
        ProfileRepository repo = MockProfileRepository.shared();
        UserProfile draft = load(repo);
        draft.setNickname("재진입");
        draft.setInterestTags(Arrays.asList("밥"));
        assertTrue(save(repo, draft).success);

        // 화면을 나갔다가 다시 들어와 새로 조회
        UserProfile reentered = load(MockProfileRepository.shared());
        assertEquals("재진입", reentered.getNickname());
        assertEquals(Arrays.asList("밥"), reentered.getInterestTags());
    }

    @Test
    public void editWithoutSave_doesNotChangeStoredProfile() {
        // 수정 화면에서 값을 바꾼 뒤 '수정 취소'로 나간 경우
        UserProfile draft = load(repository);
        draft.setNickname("저장안함");
        draft.getInterestTags().add("산책2");

        UserProfile stored = load(repository);
        assertEquals("김지민", stored.getNickname());
        assertFalse(stored.getInterestTags().contains("산책2"));
    }

    @Test
    public void failedSave_keepsPreviousSavedValuesOnMyPage() {
        ProfileRepository repo = MockProfileRepository.shared();
        UserProfile draft = load(repo);
        draft.setNickname("");
        draft.setBio("저장되면 안 되는 소개");
        assertFalse(save(repo, draft).success);

        UserProfile shown = load(repo);
        assertEquals("김지민", shown.getNickname());
        assertFalse("저장되면 안 되는 소개".equals(shown.getBio()));
    }

    // ---- 프로필 최초 생성 ----

    @Test
    public void createProfile_fromEmpty_requiresNicknameAndDepartment_thenShownOnMyPage() {
        ProfileRepository repo = MockProfileRepository.shared();
        UserProfile draft = UserProfile.empty();
        draft.setUid("mock_uid_new");
        draft.setEmail("new.user@kookmin.ac.kr");

        assertFalse(save(repo, draft).success);

        draft.setNickname("새내기");
        assertFalse(save(repo, draft).success);

        draft.setDepartment("소프트웨어학과");
        draft.setGrade("1학년");
        draft.setGender("남성");
        assertTrue(save(repo, draft).success);

        UserProfile shown = load(repo);
        assertEquals("mock_uid_new", shown.getUid());
        assertEquals("new.user@kookmin.ac.kr", shown.getEmail());
        assertEquals("새내기", shown.getNickname());
        assertEquals("소프트웨어학과 1학년 · 남성", shown.getFormattedSummary());
        assertTrue(shown.getInterestTags().isEmpty());
    }

    @Test
    public void emptyProfile_hasNoDemoValues() {
        UserProfile empty = UserProfile.empty();
        assertNull(empty.getUid());
        assertEquals("", empty.getNickname());
        assertEquals("", empty.getDepartment());
        assertEquals("", empty.getFormattedSummary());
        assertTrue(empty.getInterestTags().isEmpty());
    }

    /** 화면 회전 시 Bundle 에 저장되는 작성 중 프로필이 직렬화 후에도 유지되는지 확인. */
    @Test
    public void draftProfile_survivesSerialization() throws Exception {
        UserProfile draft = load(repository);
        draft.setNickname("회전테스트");
        draft.setInterestTags(Arrays.asList("카페", "밥"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(draft);
        }
        UserProfile restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (UserProfile) in.readObject();
        }

        assertEquals("회전테스트", restored.getNickname());
        assertEquals(Arrays.asList("카페", "밥"), restored.getInterestTags());
        assertEquals(draft.getFormattedSummary(), restored.getFormattedSummary());
    }

    // ---- 기존 목업 기능 ----

    // BT-029: Mock AI 초안 — 입력으로 정해진 초안, # 없는 추천 태그, 이미 고른 활동은 다시 추천하지 않음
    @Test
    public void generateAiProfile_mockDraftUsesInputs() {
        AtomicReference<AiProfileDraft> draftRef = new AtomicReference<>();
        repository.generateAiProfile(new AiProfileRequest("소프트웨어학과", 2, Arrays.asList("카페"),
                Arrays.asList("자료구조"), ""), new ProfileRepository.Callback<AiProfileDraft>() {
            @Override public void onSuccess(AiProfileDraft result) { draftRef.set(result); }
            @Override public void onError(String message) {}
        });

        AiProfileDraft draft = draftRef.get();
        assertNotNull(draft);
        assertTrue(draft.bio.contains("소프트웨어학과 2학년"));
        assertTrue(draft.bio.contains("자료구조"));
        assertTrue(draft.bio.length() <= ProfileValidator.BIO_MAX_LENGTH);
        assertFalse(draft.suggestedTags.contains("카페"));
        assertEquals(3, draft.suggestedTags.size());
        for (String tag : draft.suggestedTags) assertFalse(tag.startsWith("#"));
    }

    @Test
    public void generateAiProfile_missingDepartment_failsWithMessage() {
        Recorder<AiProfileDraft> recorder = new Recorder<>();
        repository.generateAiProfile(new AiProfileRequest(" ", 2, Arrays.asList(), Arrays.asList(), null), recorder);
        assertFalse(recorder.success);
        assertEquals("학과를 먼저 선택해 주세요", recorder.errorMessage);
    }

    @Test
    public void tagsList_containsExpectedOptions() {
        List<String> defaultTags = repository.getDefaultTags();
        assertTrue(defaultTags.contains("스터디"));
        assertTrue(defaultTags.contains("카페"));
        assertTrue(defaultTags.contains("산책"));
    }

    // ---- BT-014 ----

    @Test
    public void uploadProfilePhoto_returnsPerUidPath() {
        AtomicReference<String> pathRef = new AtomicReference<>();
        repository.uploadProfilePhoto("uidA", new byte[]{1, 2, 3}, new ProfileRepository.Callback<String>() {
            @Override public void onSuccess(String result) { pathRef.set(result); }
            @Override public void onError(String message) {}
        });
        assertEquals("profileImages/uidA/profile.jpg", pathRef.get());
    }

    @Test
    public void uploadProfilePhoto_rejectsEmptyBytes() {
        Recorder<String> recorder = new Recorder<>();
        repository.uploadProfilePhoto("uidA", new byte[0], recorder);
        assertFalse(recorder.success);
        assertNotNull(recorder.errorMessage);
    }

    @Test
    public void hasCompletedProfile_async_matchesSavedUid() {
        UserProfile created = UserProfile.empty();
        created.setUid("uidNew");
        created.setNickname("새친구");
        created.setDepartment("소프트웨어학부");
        repository.saveProfile(created, new Recorder<>());

        AtomicReference<Boolean> completed = new AtomicReference<>();
        repository.hasCompletedProfile("uidNew", new ProfileRepository.Callback<Boolean>() {
            @Override public void onSuccess(Boolean result) { completed.set(result); }
            @Override public void onError(String message) {}
        });
        assertEquals(Boolean.TRUE, completed.get());

        repository.hasCompletedProfile("otherUid", new ProfileRepository.Callback<Boolean>() {
            @Override public void onSuccess(Boolean result) { completed.set(result); }
            @Override public void onError(String message) {}
        });
        assertEquals(Boolean.FALSE, completed.get());
    }

    @Test
    public void copy_keepsPhotoPath() {
        UserProfile p = UserProfile.empty();
        p.setPhotoPath("profileImages/u/profile.jpg");
        assertEquals("profileImages/u/profile.jpg", p.copy().getPhotoPath());
    }

    // ---- 공개 프로필 (다른 사용자 표시용) ----

    private static final class Holder<T> implements ProfileRepository.Callback<T> {
        boolean called;
        T result;
        String error;

        @Override public void onSuccess(T value) { called = true; result = value; }
        @Override public void onError(String message) { called = true; error = message; }
    }

    private static Holder<UserProfile> loadPublic(ProfileRepository repo, String uid) {
        Holder<UserProfile> holder = new Holder<>();
        repo.getPublicProfile(uid, holder);
        return holder;
    }

    @Test
    public void getPublicProfile_afterSave_returnsCopyWithoutEmail() {
        UserProfile mine = UserProfile.empty();
        mine.setUid("uidPub");
        mine.setEmail("pub@kookmin.ac.kr");
        mine.setNickname("공개닉");
        mine.setDepartment("소프트웨어학부");
        mine.setInterestTags(Arrays.asList("카페"));
        assertTrue(save(repository, mine).success);

        Holder<UserProfile> shown = loadPublic(repository, "uidPub");
        assertNull(shown.error);
        assertEquals("공개닉", shown.result.getNickname());
        assertEquals(Arrays.asList("카페"), shown.result.getInterestTags());
        assertNull(shown.result.getEmail());

        // 받은 객체를 고쳐도 저장소 값은 그대로다.
        shown.result.setNickname("바뀜");
        assertEquals("공개닉", loadPublic(repository, "uidPub").result.getNickname());
    }

    @Test
    public void getPublicProfile_unknownUid_returnsNull() {
        Holder<UserProfile> shown = loadPublic(repository, "nobody");
        assertTrue(shown.called);
        assertNull(shown.error);
        assertNull(shown.result);
    }

    @Test
    public void getPublicProfile_nullOrEmptyUid_fails() {
        assertNotNull(loadPublic(repository, null).error);
        assertNotNull(loadPublic(repository, "").error);
    }

    @Test
    public void putPublicProfile_registersOtherUserWithoutEmail() {
        MockProfileRepository repo = new MockProfileRepository();
        UserProfile other = UserProfile.empty();
        other.setUid("candidate1");
        other.setEmail("leak@kookmin.ac.kr");
        other.setNickname("후보");
        other.setDepartment("경영학부");
        repo.putPublicProfile(other);
        repo.putPublicProfile(UserProfile.empty()); // uid 없음 → 무시

        Holder<UserProfile> shown = loadPublic(repo, "candidate1");
        assertEquals("후보", shown.result.getNickname());
        assertNull(shown.result.getEmail());
        assertEquals("leak@kookmin.ac.kr", other.getEmail()); // 원본은 건드리지 않는다
    }

    /** 공개 프로필은 다른 사용자 조회용이라 내 전체 프로필(getProfile)과 섞이지 않는다. */
    @Test
    public void getPublicProfile_doesNotExposeOwnFullProfileForOtherUid() {
        assertNull(loadPublic(repository, "u1").result); // 저장 안 한 시연 프로필은 공개 목록에 없음
    }
}
