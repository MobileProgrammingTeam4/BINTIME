package com.team4.bintime.matching;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.model.MatchCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 매칭 카드 목록. 내 선호 태그와 겹치는 태그는 Interest/Selected 스타일로 강조한다. */
public class MatchingAdapter extends RecyclerView.Adapter<MatchingAdapter.Holder> {

    private final List<MatchCandidate> items = new ArrayList<>();
    private Set<String> myTags = Collections.emptySet();

    public void submit(List<MatchCandidate> list, Set<String> selectedTags) {
        items.clear();
        items.addAll(list);
        myTags = selectedTags;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_matching_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        MatchCandidate c = items.get(position);
        MatchSummaryBinder.bind(h.summary, c, false);
        h.intro.setText(c.intro);

        h.tagRow.removeAllViews();
        addTag(h.tagRow, c.purpose, true); // 상대의 매칭 목적은 항상 강조
        for (String tag : c.tags) addTag(h.tagRow, tag, myTags.contains(tag));

        h.btnProfile.setOnClickListener(v ->
                v.getContext().startActivity(ProfileDetailActivity.intent(v.getContext(), c.uid, myTags)));
        MatchSummaryBinder.bindRequestButton(h.btnRequest, c);
    }

    static void addTag(LinearLayout row, String label, boolean selected) {
        TextView chip = ChipViews.interest(row.getContext(), label, selected);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.setMarginStart(ChipViews.dp(row.getContext(), 8));
        row.addView(chip, lp);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View summary;
        final TextView intro, btnProfile, btnRequest;
        final LinearLayout tagRow;

        Holder(View v) {
            super(v);
            summary = v.findViewById(R.id.summary);
            intro = v.findViewById(R.id.intro);
            btnProfile = v.findViewById(R.id.btnProfile);
            btnRequest = v.findViewById(R.id.btnRequest);
            tagRow = v.findViewById(R.id.tagRow);
        }
    }
}
