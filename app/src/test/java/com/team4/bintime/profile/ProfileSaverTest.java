package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * #79 리뷰 반영: 프로필 저장 단계는 Activity 와 무관하게 끝까지 진행된다.
 * (업로드 응답이 화면 재생성 뒤에 와도 프로필 저장이 호출되어야 한다.)
 */
public class ProfileSaverTest {

    /** 실행을 모아 두었다가 직접 흘려보내는 실행기. 업로드 응답 지연·화면 재생성 사이 시점을 흉내 낸다. */
    private static final class QueueExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runAll() {
            while (!queue.isEmpty()) queue.remove(0).run();
        }
    }

    /** 업로드 응답을 늦게 돌려주는 저장소. 저장 호출 횟수를 센다. */
    private static final class DelayedUploadRepository extends MockProfileRepository {
        ProfileRepository.Callback<String> pendingUpload;
        String pendingPath;
        int saveCalls;

        @Override
        public void uploadProfilePhoto(String uid, byte[] jpegBytes, Callback<String> callback) {
            pendingPath = ProfileDocuments.photoPath(uid);
            pendingUpload = callback;
        }

        @Override
        public void saveProfile(UserProfile profile, Callback<Void> callback) {
            saveCalls++;
            super.saveProfile(profile, callback);
        }
    }

    private final Executor direct = Runnable::run;
    private UserProfile draft;
    private final List<UserProfile> saved = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final ProfileSaver.Listener listener = new ProfileSaver.Listener() {
        @Override
        public void onSaved(UserProfile profile) {
            saved.add(profile);
        }

        @Override
        public void onFailed(String message) {
            failures.add(message);
        }
    };

    @Before
    public void setUp() {
        draft = UserProfile.empty();
        draft.setUid("uid1");
        draft.setNickname("닉네임");
        draft.setDepartment("컴퓨터공학과");
        draft.setGrade("3학년");
        draft.setGender("여성");
    }

    @Test
    public void withoutPhoto_savesProfileOnly() {
        DelayedUploadRepository repo = new DelayedUploadRepository();
        new ProfileSaver(repo, direct, direct).save(draft, null, "사진 실패", listener);

        assertEquals(1, repo.saveCalls);
        assertNull(repo.pendingUpload);
        assertEquals(1, saved.size());
        assertTrue(failures.isEmpty());
    }

    @Test
    public void delayedUpload_stillSavesProfileWithPhotoPath() {
        DelayedUploadRepository repo = new DelayedUploadRepository();
        QueueExecutor background = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        new ProfileSaver(repo, background, main).save(draft, () -> new byte[]{1, 2, 3}, "사진 실패", listener);

        background.runAll(); // 압축
        main.runAll();       // 업로드 요청
        assertEquals(0, repo.saveCalls);

        // (이 사이에 화면이 다시 만들어져도 저장 작업은 Activity 와 무관하다.)
        repo.pendingUpload.onSuccess(repo.pendingPath);

        assertEquals(1, repo.saveCalls);
        assertEquals(1, saved.size());
        assertEquals("profileImages/uid1/profile.jpg", saved.get(0).getPhotoPath());
    }

    @Test
    public void draftChangedAfterStart_doesNotAffectSave() {
        DelayedUploadRepository repo = new DelayedUploadRepository();
        new ProfileSaver(repo, direct, direct).save(draft, () -> new byte[]{1}, "사진 실패", listener);
        draft.setNickname("바뀐닉네임");
        repo.pendingUpload.onSuccess(repo.pendingPath);

        assertEquals("닉네임", saved.get(0).getNickname());
    }

    @Test
    public void unreadablePhoto_failsWithoutUploadOrSave() {
        DelayedUploadRepository repo = new DelayedUploadRepository();
        new ProfileSaver(repo, direct, direct).save(draft, () -> {
            throw new java.io.IOException("decode failed");
        }, "사진 실패", listener);

        assertNull(repo.pendingUpload);
        assertEquals(0, repo.saveCalls);
        assertEquals(1, failures.size());
        assertEquals("사진 실패", failures.get(0));
    }

    @Test
    public void uploadError_reportsFailureWithoutSave() {
        DelayedUploadRepository repo = new DelayedUploadRepository();
        new ProfileSaver(repo, direct, direct).save(draft, () -> new byte[]{1}, "사진 실패", listener);
        repo.pendingUpload.onError("네트워크 연결을 확인해 주세요.");

        assertEquals(0, repo.saveCalls);
        assertEquals("네트워크 연결을 확인해 주세요.", failures.get(0));
        assertTrue(saved.isEmpty());
    }
}
