package com.team4.bintime.profile;

import java.util.concurrent.Executor;

/**
 * 프로필 저장 단계: (새 사진이 있으면) 압축 → 업로드 → 프로필 저장 (BT-014, #79 리뷰 반영).
 * <p>
 * Activity 를 참조하지 않으므로 {@link ProfileSaveViewModel} 이 들고 있으면 화면이 회전해도 끝까지 진행된다.
 * 압축은 {@code background} 에서, 저장소 호출과 결과 전달은 {@code main} 에서 한다.
 * 실행기를 주입받아 JVM 단위 테스트에서도 같은 순서를 검증할 수 있다.
 */
final class ProfileSaver {

    /** 새로 고른 사진을 업로드용 JPEG 로 읽는다. 실패하면 예외 또는 null. */
    interface PhotoSource {
        byte[] readJpeg() throws Exception;
    }

    interface Listener {
        /** 저장된 프로필(업로드했다면 photoPath 포함). */
        void onSaved(UserProfile saved);

        void onFailed(String message);
    }

    private final ProfileRepository repository;
    private final Executor background;
    private final Executor main;

    ProfileSaver(ProfileRepository repository, Executor background, Executor main) {
        this.repository = repository;
        this.background = background;
        this.main = main;
    }

    /**
     * @param draft       저장할 프로필. 호출측과 공유하지 않도록 복사본을 쓴다.
     * @param photo       새 사진이 없으면 null
     * @param photoFailed 사진을 읽지 못했을 때 보여 줄 문구
     */
    void save(UserProfile draft, PhotoSource photo, String photoFailed, Listener listener) {
        UserProfile toSave = draft.copy();
        if (photo == null) {
            saveProfile(toSave, listener);
            return;
        }
        background.execute(() -> {
            byte[] jpeg;
            try {
                jpeg = photo.readJpeg();
            } catch (Exception e) {
                jpeg = null;
            }
            byte[] result = jpeg;
            main.execute(() -> {
                if (result == null || result.length == 0) {
                    listener.onFailed(photoFailed);
                    return;
                }
                repository.uploadProfilePhoto(toSave.getUid(), result, new ProfileRepository.Callback<String>() {
                    @Override
                    public void onSuccess(String path) {
                        toSave.setPhotoPath(path);
                        saveProfile(toSave, listener);
                    }

                    @Override
                    public void onError(String message) {
                        listener.onFailed(message);
                    }
                });
            });
        });
    }

    private void saveProfile(UserProfile toSave, Listener listener) {
        repository.saveProfile(toSave, new ProfileRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                listener.onSaved(toSave);
            }

            @Override
            public void onError(String message) {
                listener.onFailed(message);
            }
        });
    }
}
