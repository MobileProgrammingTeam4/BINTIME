package com.team4.bintime.profile;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.team4.bintime.R;
import com.team4.bintime.common.StateView;

/**
 * BT-045 탈퇴 처리 중 표시. 닫을 수 없고, 처리 중 다른 메뉴를 누르지 못하게 막는다.
 * Figma 에 시안이 없어 공통 StateView 로딩과 dialog 배경(bg_dialog)을 재사용한다.
 */
public class AccountDeletionProgressDialog extends DialogFragment {

    static final String TAG = "accountDeletionProgress";

    static void show(FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved()) return;
        new AccountDeletionProgressDialog().show(fm, TAG);
    }

    static void hide(FragmentManager fm) {
        DialogFragment dialog = (DialogFragment) fm.findFragmentByTag(TAG);
        if (dialog != null) dialog.dismissAllowingStateLoss();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        StateView view = new StateView(requireContext(), null);
        view.setBackgroundResource(R.drawable.bg_dialog);
        view.showLoading();
        view.setContentDescription(getString(R.string.my_delete_progress));
        Dialog dialog = new Dialog(requireContext());
        dialog.setContentView(view);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }
}
