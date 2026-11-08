package com.team4.bintime.profile;

import android.app.AlertDialog;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.MockAuthRepository;
import com.team4.bintime.auth.OnboardingActivity;
import com.team4.bintime.notification.NotificationSettingsActivity;

/**
 * 마이페이지 탭 화면 (Figma 41:24577).
 * (BT-004 / Issue #4)
 */
public class MyPageFragment extends Fragment {

    // 프로필 설정 화면과 같은 저장소를 사용해 수정 결과가 onResume() 에서 바로 반영되게 한다.
    private final MockProfileRepository profileRepo = MockProfileRepository.shared();
    private UserProfile currentProfile;

    private ImageView imgMyPhoto;
    private TextView tvMyName;
    private TextView tvMyInfo;
    private TextView tvMyBio;
    private ChipGroup chipGroupMyTags;

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

                if (profile.getPhotoResId() != 0) {
                    imgMyPhoto.setImageResource(profile.getPhotoResId());
                }

                chipGroupMyTags.removeAllViews();
                if (profile.getInterestTags() != null) {
                    for (String tag : profile.getInterestTags()) {
                        Chip chip = new Chip(requireContext());
                        chip.setText("#" + tag);
                        chip.setChipBackgroundColorResource(R.color.action_subtle);
                        chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.action_primary));
                        chip.setChipStrokeColorResource(R.color.action_soft);
                        chip.setChipStrokeWidth(1.0f);
                        chip.setClickable(false);
                        chipGroupMyTags.addView(chip);
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

        // 3. 시간표 관리 -> MainActivity의 시간표 탭으로 이동
        root.findViewById(R.id.menuTimetable).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).selectTab(R.id.nav_timetable);
            }
        });

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

    private void showLogoutDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("로그아웃")
                .setMessage("정말 로그아웃 하시겠습니까?")
                .setPositiveButton("로그아웃", (dialog, which) -> {
                    MockAuthRepository.shared().signOut();
                    Toast.makeText(requireContext(), "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(requireContext(), OnboardingActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    if (getActivity() != null) getActivity().finish();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showDeleteAccountDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("회원 탈퇴")
                .setMessage("회원 탈퇴 시 시간표와 매칭 기록이 모두 삭제됩니다. 계속하시겠습니까?")
                .setPositiveButton("탈퇴하기", (dialog, which) ->
                        Toast.makeText(requireContext(), "회원 탈퇴가 완료되었습니다.", Toast.LENGTH_SHORT).show())
                .setNegativeButton("취소", null)
                .show();
    }
}
