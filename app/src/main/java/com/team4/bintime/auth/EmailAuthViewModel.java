package com.team4.bintime.auth;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.team4.bintime.profile.ProfileRepository;

/**
 * 이메일 링크 검증 요청을 화면 회전 뒤에도 이어 가기 위한 ViewModel (BT-013 리뷰 반영).
 * <p>
 * 검증 중 Activity 가 다시 만들어져도 ViewModel 은 유지되므로, 결과를 LiveData 로 새 Activity 에 전달한다.
 * 결과는 한 번만 처리하도록 {@link Result#consume()} 로 표시한다.
 * <p>
 * (BT-014, #83 리뷰 반영) 로그인 성공 뒤의 프로필 완료 여부 조회도 여기서 이어서 한다.
 * 조회 중 회전해도 결과가 사라지지 않아, 새 Activity 가 메인/가입 단계로 이동할 수 있다.
 */
public class EmailAuthViewModel extends ViewModel {

    /**
     * 검증 결과. 성공이면 uid 가 있고, 실패면 failure(또는 message 만) 가 있다.
     * 성공 시 {@link #profileCompleted} 는 프로필 완료 여부이며, 조회에 실패했으면 null 이고 message 에 사유가 있다.
     */
    public static final class Result {
        public final String uid;
        public final String email;
        public final Boolean profileCompleted;
        public final AuthRepository.Failure failure;
        public final String message;
        private boolean consumed;

        private Result(String uid, String email, Boolean profileCompleted,
                       AuthRepository.Failure failure, String message) {
            this.uid = uid;
            this.email = email;
            this.profileCompleted = profileCompleted;
            this.failure = failure;
            this.message = message;
        }

        static Result success(String uid, String email, boolean profileCompleted) {
            return new Result(uid, email, profileCompleted, null, null);
        }

        /** 로그인은 됐지만 프로필 완료 여부를 확인하지 못한 경우. */
        static Result profileCheckFailed(String uid, String email, String message) {
            return new Result(uid, email, null, null, message);
        }

        static Result failure(AuthRepository.Failure failure, String message) {
            return new Result(null, null, null, failure, message);
        }

        public boolean isSuccess() {
            return uid != null;
        }

        /** 처음 부르면 true, 이미 처리한 결과면 false. 회전 뒤 같은 결과를 다시 처리하지 않게 한다. */
        public boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }
    }

    private final MutableLiveData<Result> verification = new MutableLiveData<>();
    private boolean verifying;

    public LiveData<Result> getVerification() {
        return verification;
    }

    public boolean isVerifying() {
        return verifying;
    }

    /**
     * 링크 검증 후 프로필 완료 여부까지 확인한다. 이미 진행 중이면 무시한다(중복 요청 방지).
     * 콜백은 메인 스레드에서 온다. 저장소는 이 호출 동안만 참조하며 Activity 는 참조하지 않는다.
     */
    public void verify(AuthRepository repository, ProfileRepository profileRepository,
                       String email, String link) {
        if (verifying) return;
        verifying = true;
        repository.verifySignInLink(email, link, new AuthRepository.Callback<String>() {
            @Override
            public void onSuccess(String uid) {
                checkProfile(profileRepository, uid, email);
            }

            @Override
            public void onError(String message) {
                verifying = false;
                verification.setValue(Result.failure(null, message));
            }

            @Override
            public void onFailure(AuthRepository.Failure failure, String message) {
                verifying = false;
                verification.setValue(Result.failure(failure, message));
            }
        });
    }

    private void checkProfile(ProfileRepository profileRepository, String uid, String email) {
        profileRepository.hasCompletedProfile(uid, new ProfileRepository.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean completed) {
                verifying = false;
                verification.setValue(Result.success(uid, email, Boolean.TRUE.equals(completed)));
            }

            @Override
            public void onError(String message) {
                verifying = false;
                verification.setValue(Result.profileCheckFailed(uid, email, message));
            }
        });
    }
}
