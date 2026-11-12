package com.team4.bintime.profile;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 프로필 저장(사진 압축·업로드·프로필 저장)을 화면 회전 뒤에도 이어 가기 위한 ViewModel (BT-014, #79 리뷰 반영).
 * <p>
 * 이전에는 Activity 가 저장을 진행해, 업로드 중 회전하면 이전 Activity 의 {@code isDestroyed()} 검사에서 멈춰
 * 사진만 올라가고 프로필은 저장되지 않았다. 이제 작업은 ViewModel 이 끝까지 진행하고,
 * Activity 는 결과(LiveData)를 받아 화면만 갱신한다. 결과는 {@link Result#consume()} 로 한 번만 처리한다.
 */
public class ProfileSaveViewModel extends ViewModel {

    public static final class Result {
        /** 성공 시 저장된 프로필, 실패 시 null. */
        public final UserProfile saved;
        public final String message;
        private boolean consumed;

        private Result(UserProfile saved, String message) {
            this.saved = saved;
            this.message = message;
        }

        static Result success(UserProfile saved) {
            return new Result(saved, null);
        }

        static Result failure(String message) {
            return new Result(null, message);
        }

        public boolean isSuccess() {
            return saved != null;
        }

        /** 처음 부르면 true, 이미 처리한 결과면 false. */
        public boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }
    }

    /** 사진 압축(디코딩)은 UI 스레드 밖에서 한다. ViewModel 이 정리될 때만 종료한다. */
    private final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private boolean saving;

    public LiveData<Result> getResult() {
        return result;
    }

    public boolean isSaving() {
        return saving;
    }

    /**
     * 저장을 시작한다. 이미 저장 중이면 무시한다(중복 저장 방지). 메인 스레드에서 호출한다.
     *
     * @param resolver Application 의 ContentResolver (Activity 를 붙잡지 않도록)
     * @param photoUri 새로 고른 사진. 없으면 null
     */
    public void save(ProfileRepository repository, ContentResolver resolver, UserProfile draft,
                     Uri photoUri, String photoFailedMessage) {
        if (saving) return;
        saving = true;
        ProfileSaver.PhotoSource photo = photoUri == null ? null
                : () -> ProfilePhotoCompressor.compress(resolver, photoUri);
        new ProfileSaver(repository, photoExecutor, mainHandler::post)
                .save(draft, photo, photoFailedMessage, new ProfileSaver.Listener() {
                    @Override
                    public void onSaved(UserProfile saved) {
                        saving = false;
                        result.setValue(Result.success(saved));
                    }

                    @Override
                    public void onFailed(String message) {
                        saving = false;
                        result.setValue(Result.failure(message));
                    }
                });
    }

    @Override
    protected void onCleared() {
        photoExecutor.shutdownNow();
    }
}
