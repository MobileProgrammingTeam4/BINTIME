package com.team4.bintime.report;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.team4.bintime.R;
import com.team4.bintime.chat.PartnerSummary;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.StateView;

import java.util.ArrayList;
import java.util.EnumSet;

/**
 * 신고하기 (Figma 116:3693). BT-010(E) 화면 목업을 BT-051(B)이 실제 접수(submitReport)와 함께 차단(BT-046)에 연결했다.
 * 제출 상태는 {@link ReportSubmitViewModel} 이 들고 있어 회전해도 이어지고, 제출 중·완료 뒤 다시 누르지 못한다.
 * 채팅 밖(프로필 등)에서도 {@link #intent} 로 열 수 있다. 매칭과 무관하면 matchId 는 null.
 */
public class ReportActivity extends AppCompatActivity {

    private static final String EXTRA_MATCH_ID = "matchId";
    private static final String EXTRA_PARTNER = "partner";
    private static final String STATE_REASONS = "reasons";

    public static Intent intent(Context context, PartnerSummary reported, @Nullable String matchId) {
        return new Intent(context, ReportActivity.class)
                .putExtra(EXTRA_PARTNER, reported.toBundle())
                .putExtra(EXTRA_MATCH_ID, matchId);
    }

    private PartnerSummary reported;
    @Nullable private String matchId;
    private final EnumSet<ReportReason> reasons = EnumSet.noneOf(ReportReason.class);
    private TextView submit;
    private EditText detail;
    private CheckBox alsoBlock;
    private ReportSubmitViewModel submission;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reported = PartnerSummary.fromBundle(getIntent().getBundleExtra(EXTRA_PARTNER));
        matchId = getIntent().getStringExtra(EXTRA_MATCH_ID);
        if (reported == null) {
            Toast.makeText(this, R.string.report_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_report);
        if (savedInstanceState != null) {
            ArrayList<String> names = savedInstanceState.getStringArrayList(STATE_REASONS);
            if (names != null) for (String name : names) reasons.add(ReportReason.valueOf(name));
        }

        findViewById(R.id.reportBack).setOnClickListener(v -> finish());
        reported.bind(findViewById(R.id.reportPartner));
        detail = findViewById(R.id.reportDetail);
        alsoBlock = findViewById(R.id.reportAlsoBlock);
        // Figma 시안처럼 기본 선택. CheckBox 상태는 회전 시 자동 복원된다.
        if (savedInstanceState == null) alsoBlock.setChecked(true);
        submit = findViewById(R.id.reportSubmit);
        submit.setOnClickListener(v -> submit());

        submission = new ViewModelProvider(this).get(ReportSubmitViewModel.class);
        buildReasons();
        updateSubmit();
        submission.getState().observe(this, this::render);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<String> names = new ArrayList<>();
        for (ReportReason reason : reasons) names.add(reason.name());
        outState.putStringArrayList(STATE_REASONS, names);
    }

    /** 신고 사유: Chip Type=Interest 를 한 줄에 하나씩 둔다. 여러 개를 고를 수 있다. */
    private void buildReasons() {
        LinearLayout list = findViewById(R.id.reportReasons);
        for (ReportReason reason : ReportReason.values()) {
            TextView chip = ChipViews.interest(this, getString(reason.label), reasons.contains(reason));
            chip.setOnClickListener(v -> {
                if (!reasons.remove(reason)) reasons.add(reason);
                v.setSelected(reasons.contains(reason));
                updateSubmit();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = ChipViews.dp(this, 8);
            list.addView(chip, params);
        }
    }

    private void updateSubmit() {
        boolean enabled = !reasons.isEmpty() && (submission == null || !submission.isBusyOrDone());
        submit.setEnabled(enabled);
        submit.setAlpha(enabled ? 1f : 0.4f);
    }

    private void submit() {
        ReportForm form = new ReportForm(reported.uid, matchId, reasons, detail.getText().toString(),
                alsoBlock.isChecked());
        if (form.validate() != null) return;
        submission.submit(ReportRepositories.get(this), form);
    }

    private void render(ReportSubmitViewModel.State state) {
        if (state == null) return;
        switch (state.status) {
            case SUBMITTING:
                updateSubmit();
                break;
            case DONE:
                showDone(state.result);
                break;
            case FAILED:
                updateSubmit();
                if (state.consumeError()) Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /** 접수 완료(또는 이미 신고함). 함께 차단을 골랐으면 차단 결과도 알려 준다. */
    private void showDone(ReportRepository.Result result) {
        findViewById(R.id.reportForm).setVisibility(View.GONE);
        StateView state = findViewById(R.id.reportState);
        String message = getString(result.alreadyReported ? R.string.report_already_message : R.string.report_done_message);
        if (result.blocked) message += "\n" + getString(R.string.report_done_blocked);
        else if (alsoBlock.isChecked()) message += "\n" + getString(R.string.report_done_block_failed);
        state.showSuccess(getString(result.alreadyReported ? R.string.report_already_title : R.string.report_done_title), message)
                .primary(getString(R.string.report_done_close), v -> finish());
    }
}
