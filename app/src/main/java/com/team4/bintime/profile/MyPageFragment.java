package com.team4.bintime.profile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.chip.ChipGroup;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.auth.SessionGuard;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.ConfirmDialog;
import com.team4.bintime.notification.NotificationSettingsActivity;
import com.team4.bintime.timetable.TimetableReviewActivity;

/**
 * 마이페이지 탭 화면 (Figma 41:24577).
 * (BT-004 / Issue #4)
 */
public class MyPageFragment extends Fragment {

    private static final String KEY_LOGOUT = "myLogout";
    private static final String KEY_DELETE_ACCOUNT = "myDeleteAccount";

    // 프로필 설정 화면과 같은 저장소를 사용해 수정 결과가 onResume() 에서 바로 반영되게 한다.
    private ProfileRepository profileRepo;
    private UserProfile currentProfile;
    /**
     * 마지막으로 표시한 사진 경로. onResume 마다 같은 사진을 다시 내려받지 않는다.
     * 사진은 항상 같은 경로(profileImages/{uid}/profile.jpg)에 덮어쓰므로, 프로필 수정이 저장되면 비운다.
     */
    private String shownPhotoPath;
    /** 프로필 수정 화면. 저장(RESULT_OK)하고 돌아오면 사진을 다시 불러온다 (#79 리뷰 반영). */
    private final ActivityResultLauncher<Intent> editProfileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) shownPhotoPath = null;
            });

    private ImageView imgMyPhoto;
    private TextView tvMyName;
    private TextView tvMyInfo;
    private TextView tvMyBio;
    private ChipGroup chipGroupMyTags;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profileRepo = ProfileRepositories.get(requireContext());
        getChildFragmentManager().setFragmentResultListener(KEY_LOGOUT, this, (key, result) -> logout());
        // BT-045: 확인하면 서버에서 탈퇴를 처리한다. 진행 상태는 ViewModel 이 들고 있어 회전 후에도 이어진다.
        deletion = new ViewModelProvider(this).get(AccountDeletionViewModel.class);
        getChildFragmentManager().setFragmentResultListener(KEY_DELETE_ACCOUNT, this, (key, result) ->
                deletion.delete(AuthRepositories.get(requireContext())));
        deletion.getState().observe(this, this::onDeletionState);
    }

    private AccountDeletionViewModel deletion;

    private void onDeletionState(AccountDeletionViewModel.State state) {
        if (state == null || !state.consume()) return;
        switch (state.status) {
            case RUNNING:
                AccountDeletionProgressDialog.show(getChildFragmentManager());
                break;
            case DONE:
                AccountDeletionProgressDialog.hide(getChildFragmentManager());
                Toast.makeText(requireContext(), R.string.my_delete_done, Toast.LENGTH_SHORT).show();
                // 서버가 계정을 지웠다. 캐시·기기 로그인을 정리하고 온보딩으로 간다(BT-030 경로 재사용).
                SessionGuard.signOutByUser(requireActivity());
                break;
            case FAILED:
                AccountDeletionProgressDialog.hide(getChildFragmentManager());
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_my_page, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        imgMyPhoto = view.findViewById(R.id.imgMyPhoto);
        tvMyName = view.findViewById(R.id.tvMyName);
        tvMyInfo = view.findViewById(R.id.tvMyInfo);
        tvMyBio = view.findViewById(R.id.tvMyBio);
        chipGroupMyTags = view.findViewById(R.id.chipGroupMyTags);

        bindMenuActions(view);
        shownPhotoPath = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadProfileData();
    }

    /** 로그인한 본인 UID 의 프로필만 불러온다 (BT-014). */
    private void loadProfileData() {
        String uid = AuthRepositories.get(requireContext()).getCurrentUid();
        profileRepo.getProfile(uid, new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile loaded) {
                if (!isAdded() || getView() == null) return;
                UserProfile profile = loaded != null ? loaded : UserProfile.empty();
                currentProfile = profile;

                tvMyName.setText(profile.getNickname());
                tvMyInfo.setText(profile.getFormattedSummary());
                tvMyBio.setText(profile.getBio());

                bindPhoto(profile);

                chipGroupMyTags.removeAllViews();
                if (profile.getInterestTags() != null) {
                    for (String tag : profile.getInterestTags()) {
                        // 공통 Figma Chip(Type=Interest, Selected)
                        chipGroupMyTags.addView(ChipViews.interest(requireContext(), "#" + tag, true));
                    }
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.profile_load_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Storage 사진 → 시연 리소스 → 기본 이미지 순. 다른 계정 사진이 남지 않도록 먼저 기본 이미지로 바꾼다. */
    private void bindPhoto(UserProfile profile) {
        String path = profile.getPhotoPath();
        if (path == null || path.isEmpty()) {
            shownPhotoPath = null;
            imgMyPhoto.setImageResource(profile.getPhotoResId() != 0
                    ? profile.getPhotoResId() : R.drawable.img_profile_placeholder);
            return;
        }
        if (path.equals(shownPhotoPath)) return;
        imgMyPhoto.setImageResource(R.drawable.img_profile_placeholder);
        profileRepo.loadProfilePhoto(path, new ProfileRepository.Callback<Bitmap>() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (!isAdded() || getView() == null) return;
                imgMyPhoto.setImageBitmap(bitmap);
                shownPhotoPath = path;
            }

            @Override
            public void onError(String message) {
                // 사진만 실패한 경우 기본 이미지를 유지한다.
            }
        });
    }

    private void bindMenuActions(View root) {
        // 1. 프로필 수정
        root.findViewById(R.id.menuEditProfile).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), ProfileSetupActivity.class);
            intent.putExtra(ProfileSetupActivity.EXTRA_MODE, ProfileSetupActivity.MODE_EDIT);
            editProfileLauncher.launch(intent);
        });

        // 2. 차단한 사용자 관리
        root.findViewById(R.id.menuBlockedUsers).setOnClickListener(v ->
                Toast.makeText(requireContext(), "차단한 사용자 관리 (후속 이슈 연동 예정)", Toast.LENGTH_SHORT).show());

        // 3. 시간표 관리
        root.findViewById(R.id.menuTimetable).setOnClickListener(v ->
                startActivity(TimetableReviewActivity.forEditing(requireContext())));

        // 4. 알림 설정 -> NotificationSettingsActivity 이동
        root.findViewById(R.id.menuNotification).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), NotificationSettingsActivity.class);
            startActivity(intent);
        });

        // 5. 이용약관 및 개인정보 처리방침
        root.findViewById(R.id.menuTerms).setOnClickListener(v ->
                Toast.makeText(requireContext(), "이용약관 및 개인정보 처리방침", Toast.LENGTH_SHORT).show());

        // 6. 로그아웃
        root.findViewById(R.id.menuLogout).setOnClickListener(v -> showLogoutDialog());

        // 7. 회원 탈퇴
        root.findViewById(R.id.menuDeleteAccount).setOnClickListener(v -> showDeleteAccountDialog());
    }

    /** 공통 ConfirmDialog(Figma Modal) 사용. 결과 리스너는 onCreate 에서 등록해 회전 후에도 받는다. */
    private void showLogoutDialog() {
        if (getChildFragmentManager().findFragmentByTag(KEY_LOGOUT) != null) return;
        ConfirmDialog.show(getChildFragmentManager(), KEY_LOGOUT,
                getString(R.string.my_logout_title), getString(R.string.my_logout_message),
                getString(R.string.my_menu_logout));
    }

    private void showDeleteAccountDialog() {
        if (deletion.isRunning() || getChildFragmentManager().findFragmentByTag(KEY_DELETE_ACCOUNT) != null) return;
        ConfirmDialog.show(getChildFragmentManager(), KEY_DELETE_ACCOUNT,
                getString(R.string.my_delete_title), getString(R.string.my_delete_message),
                getString(R.string.my_delete_confirm));
    }

    /** BT-030: 캐시 정리 → 로그아웃 → 온보딩. 세션 감시가 이 로그아웃을 '만료'로 보지 않게 SessionGuard 로 한다. */
    private void logout() {
        Toast.makeText(requireContext(), R.string.my_logout_done, Toast.LENGTH_SHORT).show();
        SessionGuard.signOutByUser(requireActivity());
    }
}
