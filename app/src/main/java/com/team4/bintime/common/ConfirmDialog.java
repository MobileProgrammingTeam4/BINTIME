package com.team4.bintime.common;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.team4.bintime.R;

/**
 * 공통 확인 다이얼로그 (Figma Modal: 제목 · 설명 · [취소] [확인 버튼]).
 * DialogFragment 라서 화면 회전·강제 종료 후에도 다시 떠 있다. 결과는 Fragment Result 로 받는다.
 * <pre>
 * // 1) onCreate 등에서 결과 받을 준비 (회전 후에도 받으려면 매번 등록)
 * getSupportFragmentManager().setFragmentResultListener("deleteCourse", this, (key, result) -> deleteCourse());
 * // 2) 띄우기
 * ConfirmDialog.show(getSupportFragmentManager(), "deleteCourse", "해당 과목을 삭제할까요", "선택한 과목을 삭제할게요", "삭제");
 * </pre>
 * 취소·바깥 터치·뒤로가기는 결과 없이 닫힌다. Fragment 에서는 getChildFragmentManager() 를 쓴다.
 */
public class ConfirmDialog extends DialogFragment {

    private static final String ARG_KEY = "requestKey";
    private static final String ARG_TITLE = "title";
    private static final String ARG_MESSAGE = "message";
    private static final String ARG_POSITIVE = "positive";

    public static void show(FragmentManager fm, String requestKey, String title, String message, String positiveLabel) {
        Bundle args = new Bundle();
        args.putString(ARG_KEY, requestKey);
        args.putString(ARG_TITLE, title);
        args.putString(ARG_MESSAGE, message);
        args.putString(ARG_POSITIVE, positiveLabel);
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setArguments(args);
        dialog.show(fm, requestKey);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm, null, false);
        ((TextView) view.findViewById(R.id.dialogTitle)).setText(args.getString(ARG_TITLE));
        ((TextView) view.findViewById(R.id.dialogMessage)).setText(args.getString(ARG_MESSAGE));
        TextView positive = view.findViewById(R.id.dialogPositive);
        positive.setText(args.getString(ARG_POSITIVE));

        view.findViewById(R.id.dialogNegative).setOnClickListener(v -> dismiss());
        positive.setOnClickListener(v -> {
            getParentFragmentManager().setFragmentResult(args.getString(ARG_KEY), new Bundle());
            dismiss();
        });
        return new AlertDialog.Builder(requireContext()).setView(view).create();
    }

    @Override
    public void onStart() {
        super.onStart();
        styleWindow(this);
    }

    /** 기본 다이얼로그 배경 대신 내용 배경(흰색·16dp 라운드)만 보이게, 폭은 Figma 300dp */
    static void styleWindow(DialogFragment fragment) {
        Dialog dialog = fragment.getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(ChipViews.dp(fragment.requireContext(), 300), ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
