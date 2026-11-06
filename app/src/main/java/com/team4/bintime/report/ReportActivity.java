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

import com.team4.bintime.R;
import com.team4.bintime.chat.PartnerSummary;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.StateView;

import java.util.ArrayList;
import java.util.EnumSet;

/**
 * 신고하기 (Figma 116:3693). BT-010 은 화면 목업과 입력 계약({@link ReportForm})까지만 만든다.
 * 실제 접수·저장·권한은 BT-051, 차단은 BT-046 에서 B 가 이 패키지를 인계받아 연결한다.
 * 채팅 밖(프로필 등)에서도 {@link #intent} 로 열 수 있다. 매칭과 무관하면 matchId 는 null.
 */
public class ReportActivity extends AppCompatActivity {

    private static final String EXTRA_MATCH_ID = "matchId";
    private static final String EXTRA_PARTNER = "partner";
    private static final String STATE_REASONS = "reasons";
    private static final String STATE_DONE = "done";

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
    private boolean done;

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
            done = savedInstanceState.getBoolean(STATE_DONE);
        }

        findViewById(R.id.reportBack).setOnClickListener(v -> finish());
        reported.bind(findViewById(R.id.reportPartner));
        detail = findViewById(R.id.reportDetail);
        alsoBlock = findViewById(R.id.reportAlsoBlock);
        // Figma 시안처럼 기본 선택. CheckBox 상태는 회전 시 자동 복원된다.
        if (savedInstanceState == null) alsoBlock.setChecked(true);
        submit = findViewById(R.id.reportSubmit);
        submit.setOnClickListener(v -> submit());

        buildReasons();
        updateSubmit();
        if (done) showDone();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<String> names = new ArrayList<>();
        for (ReportReason reason : reasons) names.add(reason.name());
        outState.putStringArrayList(STATE_REASONS, names);
        outState.putBoolean(STATE_DONE, done);
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
        boolean enabled = !reasons.isEmpty();
        submit.setEnabled(enabled);
        submit.setAlpha(enabled ? 1f : 0.4f);
    }

    private void submit() {
        if (done) return;
        ReportForm form = new ReportForm(reported.uid, matchId, reasons, detail.getText().toString(),
                alsoBlock.isChecked());
        if (form.validate() != null) return;
        // BT-051(B): 여기서 ReportRepository.submit(form, callback) 을 호출하고, 성공 시 showDone() 으로 바꾼다.
        // BT-010 은 저장하지 않는다.
        done = true;
        showDone();
    }

    private void showDone() {
        findViewById(R.id.reportForm).setVisibility(View.GONE);
        StateView state = findViewById(R.id.reportState);
        state.showSuccess(getString(R.string.report_done_title), getString(R.string.report_done_message))
                .primary(getString(R.string.report_done_close), v -> finish());
    }
}
