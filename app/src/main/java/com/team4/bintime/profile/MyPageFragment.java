package com.team4.bintime.profile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.ChipGroup;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.auth.OnboardingActivity;
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
    private final MockProfileRepository profileRepo = MockProfileRepository.shared();
    private UserProfile currentProfile;

    private ImageView imgMyPhoto;
    private TextView tvMyName;
    private TextView tvMyInfo;
    private TextView tvMyBio;
    private ChipGroup chipGroupMyTags;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getChildFragmentManager().setFragmentResultListener(KEY_LOGOUT, this, (key, result) -> logout());
        // 실제 계정 삭제는 후속 이슈 범위라, 완료된 것처럼 안내하지 않는다.
        getChildFragmentManager().setFragmentResultListener(KEY_DELETE_ACCOUNT, this, (key, result) ->
                Toast.makeText(requireContext(), R.string.my_delete_not_ready, Toast.LENGTH_SHORT).show());
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
        loadProfileData();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadProfileData();
    }

    private void loadProfileData() {
        profileRepo.getProfile("u1", new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile profile) {
                currentProfile = profile;
                if (!isAdded()) return;

                tvMyName.setText(profile.getNickname());
                tvMyInfo.setText(profile.getFormattedSummary());
                tvMyBio.setText(profile.getBio());

                imgMyPhoto.setImageResource(profile.getPhotoResId() != 0
                        ? profile.getPhotoResId() : R.drawable.img_profile_placeholder);

                chipGroupMyTags.removeAllViews();
                if (profile.getInterestTags() != null) {
                    for (String tag : profile.getInterestTags()) {
                        // 공통 Figma Chip(Type=Interest, Selected)
                        chipGroupMyTags.addView(ChipViews.interest(requireContext(), "#" + tag, true));
                    }
                }
            }

            @Override
            public void onError(String message) {}
        });
    }

    private void bindMenuActions(View root) {
        // 1. 프로필 수정
        root.findViewById(R.id.menuEditProfile).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), ProfileSetupActivity.class);
            intent.putExtra(ProfileSetupActivity.EXTRA_MODE, ProfileSetupActivity.MODE_EDIT);
            startActivity(intent);
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
        if (getChildFragmentManager().findFragmentByTag(KEY_DELETE_ACCOUNT) != null) return;
        ConfirmDialog.show(getChildFragmentManager(), KEY_DELETE_ACCOUNT,
                getString(R.string.my_delete_title), getString(R.string.my_delete_message),
                getString(R.string.my_delete_confirm));
    }

    private void logout() {
        AuthRepositories.get(requireContext()).signOut();
        Toast.makeText(requireContext(), R.string.my_logout_done, Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(requireContext(), OnboardingActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
}
