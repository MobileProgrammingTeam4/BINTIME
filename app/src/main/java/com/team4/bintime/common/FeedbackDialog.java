package com.team4.bintime.common;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.team4.bintime.R;

/**
 * 완료 안내 팝업 (Figma 빈틈 매칭/Feedback Type=Success: 체크 아이콘 · 제목 · 설명 · [확인]).
 * DialogFragment 라서 화면 회전 후에도 남아 있다.
 * 예) FeedbackDialog.success(getSupportFragmentManager(), "매칭 신청이 완료됐어요!", "김지민님이 수락하면\n채팅을 시작할 수 있어요.");
 */
public class FeedbackDialog extends DialogFragment {

    private static final String TAG = "feedback";
    private static final String ARG_TITLE = "title";
    private static final String ARG_MESSAGE = "message";

    public static void success(FragmentManager fm, String title, String message) {
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_MESSAGE, message);
        FeedbackDialog dialog = new FeedbackDialog();
        dialog.setArguments(args);
        dialog.show(fm, TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        StateView view = new StateView(requireContext(), null);
        view.setBackgroundResource(R.drawable.bg_dialog);
        view.showSuccess(args.getString(ARG_TITLE), args.getString(ARG_MESSAGE)).primary("확인", v -> dismiss());
        return new AlertDialog.Builder(requireContext()).setView(view).create();
    }

    @Override
    public void onStart() {
        super.onStart();
        ConfirmDialog.styleWindow(this);
    }
}
