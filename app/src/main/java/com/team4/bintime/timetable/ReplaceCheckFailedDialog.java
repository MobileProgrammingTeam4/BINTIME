package com.team4.bintime.timetable;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;

/**
 * [BT-026] 교체 확인에서 기존 수업 수를 서버에서 읽지 못했을 때의 확인 창: [다시 확인] [취소] [교체하기].
 * 공통 {@link com.team4.bintime.common.ConfirmDialog}는 버튼이 둘이라 시간표 화면 전용으로 둔다.
 * 결과는 Fragment Result 로 보낸다. [교체하기] → {@link #KEY_CONFIRM}, [다시 확인] → {@link #KEY_RETRY}.
 * 취소·바깥 터치·뒤로가기는 결과 없이 닫힌다(저장하지 않는다).
 */
public class ReplaceCheckFailedDialog extends DialogFragment {

    static final String TAG = "timetable_replace_check_failed";
    static final String KEY_CONFIRM = "timetable_replace_failed_confirm";
    static final String KEY_RETRY = "timetable_replace_failed_retry";

    static void show(FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved()) return;
        new ReplaceCheckFailedDialog().show(fm, TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_replace_check_failed, null, false);
        view.findViewById(R.id.replaceFailedCancel).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.replaceFailedConfirm).setOnClickListener(v -> sendAndClose(KEY_CONFIRM));
        view.findViewById(R.id.replaceFailedRetry).setOnClickListener(v -> sendAndClose(KEY_RETRY));
        return new AlertDialog.Builder(requireContext()).setView(view).create();
    }

    /** 다시 확인이 곧바로 같은 창을 다시 띄울 수 있도록 먼저 닫고(즉시) 결과를 보낸다. */
    private void sendAndClose(String key) {
        FragmentManager fm = getParentFragmentManager();
        dismiss();
        fm.executePendingTransactions();
        fm.setFragmentResult(key, new Bundle());
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(ChipViews.dp(requireContext(), 300), ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
