package com.team4.bintime.matching;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.common.ChipViews;

import java.util.List;

/** 매칭 필터 설정: 겹치는 공강시간(단일) · 목적(단일) · 선호 태그(복수). 적용 시 결과를 돌려준다. */
public class FilterActivity extends AppCompatActivity {

    public static final String EXTRA_FILTER = "filter";

    private MatchFilter filter;
    private ViewGroup groupOverlap, groupPurpose, groupAiTags, groupBaseTags;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter);

        // 회전 등으로 다시 만들어질 땐 고르던 값을, 처음 열릴 땐 메인에서 넘긴 값을 사용
        MatchFilter given = (MatchFilter) (savedInstanceState != null
                ? savedInstanceState.getSerializable(EXTRA_FILTER)
                : getIntent().getSerializableExtra(EXTRA_FILTER));
        filter = given != null ? given.copy() : MatchFilter.defaults();

        groupOverlap = findViewById(R.id.groupOverlap);
        groupPurpose = findViewById(R.id.groupPurpose);
        groupAiTags = findViewById(R.id.groupAiTags);
        groupBaseTags = findViewById(R.id.groupBaseTags);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnReset).setOnClickListener(v -> {
            filter = MatchFilter.defaults();
            bind();
        });
        findViewById(R.id.btnApply).setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_FILTER, filter));
            finish();
        });
        bind();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(EXTRA_FILTER, filter);
    }

    private void bind() {
        groupOverlap.removeAllViews();
        for (int minutes : MatchFilter.OVERLAP_OPTIONS) {
            TextView chip = ChipViews.filter(this, MatchFilter.formatDuration(minutes) + " 이상",
                    minutes == filter.minOverlapMinutes);
            chip.setOnClickListener(v -> {
                filter.minOverlapMinutes = minutes;
                bind();
            });
            groupOverlap.addView(chip);
        }

        groupPurpose.removeAllViews();
        for (String purpose : MatchFilter.PURPOSE_OPTIONS) {
            TextView chip = ChipViews.filter(this, purpose, purpose.equals(filter.purpose));
            chip.setOnClickListener(v -> {
                filter.purpose = purpose;
                bind();
            });
            groupPurpose.addView(chip);
        }

        bindTags(groupAiTags, MatchFilter.AI_TAGS);
        bindTags(groupBaseTags, MatchFilter.BASE_TAGS);
    }

    private void bindTags(ViewGroup group, List<String> tags) {
        group.removeAllViews();
        for (String tag : tags) {
            TextView chip = ChipViews.interest(this, tag, filter.tags.contains(tag));
            chip.setOnClickListener(v -> {
                if (!filter.tags.remove(tag)) filter.tags.add(tag);
                v.setSelected(filter.tags.contains(tag));
            });
            group.addView(chip);
        }
    }
}
