package com.team4.bintime.chat;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;

/**
 * 버튼 하나짜리 안내 (Figma 120:2575 "채팅방을 나갈 수 없어요" · [닫기]).
 * 공통 ConfirmDialog 가 버튼 2개만 지원해서, common/ 을 고치지 않고 같은 dialog_confirm.xml 을 재사용한다.
 * ConfirmDialog 에 버튼 1개 옵션이 생기면 그쪽으로 옮긴다(A 와 협의).
 */
public class ChatNoticeDialog extends DialogFragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_MESSAGE = "message";
    private static final String ARG_BUTTON = "button";

    public static void show(FragmentManager fm, String tag, String title, String message, String buttonLabel) {
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_MESSAGE, message);
        args.putString(ARG_BUTTON, buttonLabel);
        ChatNoticeDialog dialog = new ChatNoticeDialog();
        dialog.setArguments(args);
        dialog.show(fm, tag);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm, null, false);
        ((TextView) view.findViewById(R.id.dialogTitle)).setText(args.getString(ARG_TITLE));
        ((TextView) view.findViewById(R.id.dialogMessage)).setText(args.getString(ARG_MESSAGE));
        view.findViewById(R.id.dialogPositive).setVisibility(View.GONE);
        // 테두리 버튼 하나만 전체 폭으로 남긴다.
        TextView close = view.findViewById(R.id.dialogNegative);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) close.getLayoutParams();
        params.setMarginEnd(0);
        close.setLayoutParams(params);
        close.setText(args.getString(ARG_BUTTON));
        close.setOnClickListener(v -> dismiss());
        return new AlertDialog.Builder(requireContext()).setView(view).create();
    }

    @Override
    public void onStart() {
        super.onStart();
        // ConfirmDialog.styleWindow 가 package-private 이라 같은 설정(투명 배경 · 폭 300dp)을 둔다.
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(ChipViews.dp(requireContext(), 300), ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
