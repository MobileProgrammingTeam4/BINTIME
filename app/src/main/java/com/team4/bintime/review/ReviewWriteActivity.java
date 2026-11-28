package com.team4.bintime.review;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.chat.PartnerSummary;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.StateView;
import com.team4.bintime.report.ReportActivity;

import java.util.ArrayList;
import java.util.EnumSet;

/**
 * 매칭 후기 남기기 (Figma 116:3395). 만족도는 필수, 키워드(복수)와 한 줄 후기는 선택이다.
 * 채팅방 후기 버튼과 ReviewEntry(완료된 매칭·알림)에서 들어온다. 작성 자격·중복은 서버(submitReview)가 검사한다.
 */
public class ReviewWriteActivity extends AppCompatActivity {

    private static final String EXTRA_MATCH_ID = "matchId";
    private static final String EXTRA_PARTNER = "partner";
    private static final String STATE_SATISFACTION = "satisfaction";
    private static final String STATE_KEYWORDS = "keywords";
    private static final String STATE_DONE = "done";

    public static Intent intent(Context context, String matchId, PartnerSummary partner) {
        return new Intent(context, ReviewWriteActivity.class)
                .putExtra(EXTRA_MATCH_ID, matchId)
                .putExtra(EXTRA_PARTNER, partner.toBundle());
    }

    private ReviewRepository repository;

    private String matchId;
    private PartnerSummary partner;
    private Satisfaction satisfaction;
    private final EnumSet<ReviewKeyword> keywords = EnumSet.noneOf(ReviewKeyword.class);
    private final ArrayList<TextView> satisfactionChips = new ArrayList<>();
    private TextView submit;
    private EditText comment;
    private boolean submitting, done;

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
        setContentView(R.layout.activity_review_write);
        restore(savedInstanceState);

        findViewById(R.id.reviewBack).setOnClickListener(v -> finish());
        partner.bind(findViewById(R.id.reviewPartner));
        comment = findViewById(R.id.reviewComment);
        submit = findViewById(R.id.reviewSubmit);
        submit.setOnClickListener(v -> submit());
        TextView notMet = findViewById(R.id.reviewNotMet);
        notMet.setText(getString(R.string.review_not_met, partner.name));
        // 만나지 못했다면 노쇼 신고로 이어지게 한다.
        notMet.setOnClickListener(v -> startActivity(ReportActivity.intent(this, partner, matchId)));

        buildSatisfaction();
        buildKeywords();
        updateSubmit();
        if (done) showDone();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (satisfaction != null) outState.putString(STATE_SATISFACTION, satisfaction.name());
        ArrayList<String> names = new ArrayList<>();
        for (ReviewKeyword keyword : keywords) names.add(keyword.name());
        outState.putStringArrayList(STATE_KEYWORDS, names);
        outState.putBoolean(STATE_DONE, done);
    }

    private void restore(@Nullable Bundle state) {
        if (state == null) return;
        String saved = state.getString(STATE_SATISFACTION);
        if (saved != null) satisfaction = Satisfaction.valueOf(saved);
        ArrayList<String> names = state.getStringArrayList(STATE_KEYWORDS);
        if (names != null) for (String name : names) keywords.add(ReviewKeyword.valueOf(name));
        done = state.getBoolean(STATE_DONE);
    }

    /** 3단계 만족도: Chip Type=Filter 를 같은 폭으로 나란히 둔다. 하나만 선택된다. */
    private void buildSatisfaction() {
        LinearLayout row = findViewById(R.id.reviewSatisfaction);
        for (Satisfaction option : Satisfaction.values()) {
            TextView chip = ChipViews.filter(this, getString(option.label), option == satisfaction);
            chip.setGravity(Gravity.CENTER);
            chip.setMaxLines(1);
            chip.setOnClickListener(v -> {
                satisfaction = option;
                for (int i = 0; i < satisfactionChips.size(); i++) {
                    satisfactionChips.get(i).setSelected(Satisfaction.values()[i] == option);
                }
                updateSubmit();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ChipViews.dp(this, 44), 1f);
            if (row.getChildCount() > 0) params.setMarginStart(ChipViews.dp(this, 8));
            row.addView(chip, params);
            satisfactionChips.add(chip);
        }
    }

    /** 키워드: Chip Type=Interest 를 한 줄에 하나씩 둔다. 여러 개를 고를 수 있다. */
    private void buildKeywords() {
        LinearLayout list = findViewById(R.id.reviewKeywords);
        for (ReviewKeyword keyword : ReviewKeyword.values()) {
            TextView chip = ChipViews.interest(this, getString(keyword.label), keywords.contains(keyword));
            chip.setOnClickListener(v -> {
                if (!keywords.remove(keyword)) keywords.add(keyword);
                v.setSelected(keywords.contains(keyword));
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = ChipViews.dp(this, 8);
            list.addView(chip, params);
        }
    }

    private void updateSubmit() {
        boolean enabled = satisfaction != null && !submitting;
        submit.setEnabled(enabled);
        submit.setAlpha(enabled ? 1f : 0.4f);
    }

    private void submit() {
        if (satisfaction == null || submitting || done) return;
        submitting = true;
        updateSubmit();
        repository.submitReview(matchId, satisfaction, keywords, comment.getText().toString(),
                new ReviewRepository.SubmitCallback() {
                    @Override
                    public void onSuccess() {
                        if (isDestroyed()) return;
                        submitting = false;
                        done = true;
                        showDone();
                    }

                    @Override
                    public void onError(ReviewFailure failure) {
                        if (isDestroyed()) return;
                        submitting = false;
                        handleFailure(failure);
                    }
                });
    }

    /** 이미 썼으면 조회 화면으로, 대상이 아니면 닫고, 일시 오류면 입력을 그대로 두고 다시 시도하게 한다. */
    private void handleFailure(ReviewFailure failure) {
        switch (failure) {
            case ALREADY_REVIEWED:
                Toast.makeText(this, R.string.review_already_written, Toast.LENGTH_SHORT).show();
                startActivity(ReviewDetailActivity.intent(this, matchId, partner));
                finish();
                return;
            case NOT_ELIGIBLE:
            case PERMISSION:
                Toast.makeText(this, R.string.review_not_eligible, Toast.LENGTH_SHORT).show();
                finish();
                return;
            case PARTNER_DELETED:
                Toast.makeText(this, R.string.review_partner_deleted, Toast.LENGTH_SHORT).show();
                finish();
                return;
            default:
                updateSubmit();
                Toast.makeText(this, R.string.review_submit_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showDone() {
        findViewById(R.id.reviewForm).setVisibility(View.GONE);
        StateView state = findViewById(R.id.reviewState);
        state.showSuccess(getString(R.string.review_done_title), getString(R.string.review_done_message, partner.name))
                .secondary(getString(R.string.review_done_back), v -> finish())
                .primary(getString(R.string.review_done_view), v -> {
                    startActivity(ReviewDetailActivity.intent(this, matchId, partner));
                    finish();
                });
    }
}
