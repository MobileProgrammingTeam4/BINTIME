package com.team4.bintime.notification;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.matching.MatchSummaryBinder;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.request.MockRequestRepository;
import com.team4.bintime.request.RequestTabsController;

public class MatchRequestDialog extends DialogFragment {
    private FirebaseRequestPopupController firebase;
    private static final java.util.Set<FragmentManager> LAUNCHING = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    public static final String RESULT = "receivedRequestResult";
    public static void showRequest(FragmentManager fm, String id) {
        if (fm.isStateSaved() || fm.findFragmentByTag(RESULT) != null || !LAUNCHING.add(fm)) return;
        MatchRequestDialog dialog = new MatchRequestDialog();
        Bundle args = new Bundle(); args.putString("id", id); dialog.setArguments(args);
        try { dialog.show(fm.beginTransaction().runOnCommit(() -> LAUNCHING.remove(fm)), RESULT); }
        catch (RuntimeException error) { LAUNCHING.remove(fm); throw error; }
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle saved) {
        Dialog dialog = new Dialog(requireContext());
        View root = getLayoutInflater().inflate(R.layout.dialog_match_request, null);
        dialog.setContentView(root);
        if (com.team4.bintime.auth.AuthRepositories.isFirebaseEnabled(requireContext())) {
            firebase = new FirebaseRequestPopupController(this, root, requireArguments().getString("id"));
            return dialog;
        }
        MockRequestRepository.Item item = ((MainActivity) requireActivity()).requests().find(requireArguments().getString("id"));
        MatchCandidate profile = item == null ? null : MockMatchRepository.find(item.profileUid);
        root.findViewById(R.id.popupClose).setOnClickListener(v -> dismiss());
        boolean pending = profile != null && !item.sent && item.status() == MockRequestRepository.Status.PENDING;
        if (profile != null) {
            View summary = root.findViewById(R.id.summary);
            MatchSummaryBinder.bind(summary, profile, false);
            summary.findViewById(R.id.avatar).setVisibility(View.GONE);
            ((TextView) summary.findViewById(R.id.time)).setText(RequestTabsController.timeLabel(item));
            ((TextView) summary.findViewById(R.id.duration)).setText(MatchFilter.formatDuration(item.endMinute-item.startMinute)+" 겹침");
            ((TextView) root.findViewById(R.id.popupIntro)).setText(profile.intro);
            LinearLayout tags = root.findViewById(R.id.popupTags);
            tags.addView(ChipViews.interest(requireContext(), profile.purpose, true));
            for (String label : profile.tags) {
                TextView chip = ChipViews.interest(requireContext(), label, tags.getChildCount() < 3);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2,-2);
                if (tags.getChildCount()>0) params.setMarginStart(ChipViews.dp(requireContext(),8));
                tags.addView(chip, params);
            }
        }
        root.findViewById(R.id.popupAccept).setEnabled(pending);
        root.findViewById(R.id.popupReject).setEnabled(pending);
        if (!pending) ((TextView) root.findViewById(R.id.popupQuestion)).setText(R.string.notification_processed);
        root.findViewById(R.id.popupAccept).setOnClickListener(v -> respond(root,"ACCEPTED"));
        root.findViewById(R.id.popupReject).setOnClickListener(v -> respond(root,"REJECTED"));
        NotificationPreferences prefs = new NotificationPreferences(requireContext());
        CheckBox today = root.findViewById(R.id.popupToday);
        today.setChecked(prefs.suppressedToday());
        today.setOnCheckedChangeListener((button,checked)->prefs.suppressToday(checked));
        return dialog;
    }
    private void respond(View root, String status) {
        root.findViewById(R.id.popupAccept).setEnabled(false);
        root.findViewById(R.id.popupReject).setEnabled(false);
        Bundle result = new Bundle(); result.putString("id",requireArguments().getString("id")); result.putString("status",status);
        getParentFragmentManager().setFragmentResult(RESULT,result);
        dismiss();
    }
    @Override public void onStart() {
        super.onStart();
        if (firebase != null) firebase.start();
        if (requireDialog().getWindow()==null) return;
        requireDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        requireDialog().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        requireDialog().getWindow().setDimAmount(.7f);
        requireDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    @Override public void onStop() { if (firebase != null) firebase.close(); super.onStop(); }
}
