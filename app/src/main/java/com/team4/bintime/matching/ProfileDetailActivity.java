package com.team4.bintime.matching;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.model.MatchCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 매칭 상대 프로필 상세 (BT-011): 요약 · AI 추천 장소 · 자기소개 · 선호태그 · 받은 후기 · [매칭 신청]. */
public class ProfileDetailActivity extends AppCompatActivity {

    private static final String EXTRA_UID = "uid";
    private static final String EXTRA_MY_TAGS = "myTags";

    public static Intent intent(Context context, String uid, Collection<String> myTags) {
        return new Intent(context, ProfileDetailActivity.class)
                .putExtra(EXTRA_UID, uid)
                .putStringArrayListExtra(EXTRA_MY_TAGS, new ArrayList<>(myTags));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MatchCandidate c = MockMatchRepository.find(getIntent().getStringExtra(EXTRA_UID));
        if (c == null) { // 잘못된 uid 로 열리면 빈 화면 대신 닫기
            finish();
            return;
        }
        setContentView(R.layout.activity_profile_detail);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        // shortcut: 차단·신고 메뉴는 B 담당 기능 연결 전까지 안내만
        findViewById(R.id.btnMore).setOnClickListener(v ->
                Toast.makeText(this, "차단·신고 메뉴는 B 담당 기능과 연결 예정", Toast.LENGTH_SHORT).show());

        MatchSummaryBinder.bind(findViewById(R.id.summary), c, true);
        ((TextView) findViewById(R.id.purpose)).setText("매칭 목적 - " + c.purpose);
        ((TextView) findViewById(R.id.intro)).setText(c.intro);

        List<String> myTags = getIntent().getStringArrayListExtra(EXTRA_MY_TAGS);
        ViewGroup tags = findViewById(R.id.tags);
        for (String tag : c.tags) {
            tags.addView(ChipViews.interest(this, tag, myTags != null && myTags.contains(tag)));
        }

        bindReviews(c);
        MatchSummaryBinder.bindRequestButton(findViewById(R.id.btnRequest), c);
    }

    private void bindReviews(MatchCandidate c) {
        boolean empty = c.reviews.isEmpty();
        findViewById(R.id.reviewList).setVisibility(empty ? View.GONE : View.VISIBLE);
        findViewById(R.id.reviewNote).setVisibility(empty ? View.GONE : View.VISIBLE);
        findViewById(R.id.rematchRate).setVisibility(empty ? View.GONE : View.VISIBLE);
        findViewById(R.id.reviewEmpty).setVisibility(empty ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.rematchRate)).setText("재만남 희망률 " + c.rematchRate + "%");

        LinearLayout list = findViewById(R.id.reviewList);
        for (MatchCandidate.Review review : c.reviews) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = new TextView(this);
            label.setTextAppearance(R.style.TextAppearance_BinTime_BodyS);
            label.setText(review.label);
            TextView count = new TextView(this);
            count.setTextAppearance(R.style.TextAppearance_BinTime_CaptionBold);
            count.setTextColor(ContextCompat.getColor(this, R.color.action_primary));
            count.setText(review.count + "명");
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(count);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (list.getChildCount() > 0) lp.topMargin = ChipViews.dp(this, 12);
            list.addView(row, lp);
        }
    }
}
