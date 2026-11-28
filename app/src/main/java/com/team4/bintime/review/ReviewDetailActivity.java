package com.team4.bintime.review;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.chat.PartnerSummary;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.StateView;

/**
 * 매칭 후기 보기 (Figma 120:2437). 상대가 나에게 남긴 후기를 보여준다.
 * 내가 후기를 쓰기 전에는 잠겨 있고(LOCKED), 상대가 아직 안 썼으면 빈 상태(NOT_YET)를 보여준다.
 */
public class ReviewDetailActivity extends AppCompatActivity {

    private static final String EXTRA_MATCH_ID = "matchId";
    private static final String EXTRA_PARTNER = "partner";

    public static Intent intent(Context context, String matchId, PartnerSummary partner) {
        return new Intent(context, ReviewDetailActivity.class)
                .putExtra(EXTRA_MATCH_ID, matchId)
                .putExtra(EXTRA_PARTNER, partner.toBundle());
    }

    private ReviewRepository repository;

    private String matchId;
    private PartnerSummary partner;
    private View content;
    private StateView state;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        matchId = getIntent().getStringExtra(EXTRA_MATCH_ID);
        partner = PartnerSummary.fromBundle(getIntent().getBundleExtra(EXTRA_PARTNER));
        if (matchId == null || partner == null) {
            Toast.makeText(this, R.string.review_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        repository = ReviewRepositories.get(this);
        setContentView(R.layout.activity_review_detail);
        findViewById(R.id.reviewDetailBack).setOnClickListener(v -> finish());
        content = findViewById(R.id.reviewDetailContent);
        state = findViewById(R.id.reviewDetailState);
        load();
    }

    private void load() {
        content.setVisibility(View.GONE);
        state.showLoading();
        repository.loadMatchReview(matchId, new ReviewRepository.MatchReviewCallback() {
            @Override
            public void onResult(MatchReview result) {
                if (isDestroyed()) return;
                if (result.visibility == ReviewRepository.Visibility.VISIBLE && result.received != null) {
                    bind(result.received);
                } else if (result.visibility == ReviewRepository.Visibility.LOCKED && !result.canWrite) {
                    // 내 후기도 없고 쓸 수도 없는 약속(진행 중·시작 전 종료·탈퇴 종료·상대 탈퇴): 작성 버튼 없이 안내만.
                    state.showEmpty(getString(R.string.review_not_target_title), getString(R.string.review_not_target_message));
                } else if (result.visibility == ReviewRepository.Visibility.LOCKED) {
                    state.showEmpty(getString(R.string.review_locked_title),
                                    getString(R.string.review_locked_message, partner.name))
                            .primary(getString(R.string.review_submit), v -> {
                                startActivity(ReviewWriteActivity.intent(ReviewDetailActivity.this, matchId, partner));
                                finish();
                            });
                } else {
                    state.showEmpty(getString(R.string.review_not_yet_title),
                            getString(R.string.review_not_yet_message, partner.name));
                }
            }

            @Override
            public void onError(ReviewFailure failure) {
                if (isDestroyed()) return;
                state.showError(getString(R.string.review_load_error))
                        .primary(getString(R.string.review_retry), v -> load());
            }
        });
    }

    private void bind(Review review) {
        state.hide();
        content.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.reviewDetailTitle)).setText(getString(R.string.review_detail_title, partner.name));
        partner.bind(findViewById(R.id.reviewDetailPartner));

        findViewById(R.id.reviewDetailKeywordSection).setVisibility(review.keywords.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.reviewDetailKeywordTitle))
                .setText(getString(R.string.review_detail_keywords, partner.name));
        LinearLayout list = findViewById(R.id.reviewDetailKeywords);
        list.removeAllViews();
        for (ReviewKeyword keyword : review.keywords) {
            TextView chip = ChipViews.interest(this, getString(keyword.label), true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = ChipViews.dp(this, 8);
            list.addView(chip, params);
        }

        findViewById(R.id.reviewDetailCommentSection).setVisibility(review.comment == null ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.reviewDetailComment)).setText(review.comment);
    }
}
