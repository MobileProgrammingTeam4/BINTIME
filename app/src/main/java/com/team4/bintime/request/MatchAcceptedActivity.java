package com.team4.bintime.request;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;

/** Figma 매칭 수락 후 확인. 요청 시점의 확정 스냅샷으로 표시한다. */
public class MatchAcceptedActivity extends AppCompatActivity {
    private static final String EXTRA_REQUEST = "acceptedRequest";
    public static final String EXTRA_OPEN_CHAT = "openChat";

    public static Intent intent(Context context, MockRequestRepository.Item item) {
        return new Intent(context, MatchAcceptedActivity.class).putExtra(EXTRA_REQUEST, item);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MockRequestRepository.Item item = (MockRequestRepository.Item) getIntent().getSerializableExtra(EXTRA_REQUEST);
        MatchCandidate profile = item == null ? null : MockMatchRepository.find(item.profileUid);
        if (profile == null || item.status() != MockRequestRepository.Status.ACCEPTED) {
            finish();
            return;
        }
        setContentView(R.layout.activity_match_accepted);
        ((TextView) findViewById(R.id.acceptedMessage)).setText(getString(R.string.request_accepted_message, profile.name));
        ((TextView) findViewById(R.id.time)).setText(RequestTabsController.timeLabel(item));
        ((TextView) findViewById(R.id.duration)).setText(getString(R.string.request_accepted_duration,
                MatchFilter.formatDuration(item.endMinute - item.startMinute)));
        ((TextView) findViewById(R.id.reasons)).setText(String.join("\n", profile.reasons));
        LinearLayout tags = findViewById(R.id.acceptedTags);
        // Figma 확인 화면은 관심 태그 3개를 표시한다. 실제 프로필에서 가져온다.
        for (String label : profile.tags.subList(0, Math.min(3, profile.tags.size()))) {
            TextView chip = ChipViews.interest(this, label, true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (tags.getChildCount() > 0) params.setMarginStart(ChipViews.dp(this, 8));
            tags.addView(chip, params);
        }
        findViewById(R.id.acceptedBack).setOnClickListener(v -> finish());
        findViewById(R.id.acceptedHome).setOnClickListener(v -> navigate(false));
        findViewById(R.id.acceptedChat).setOnClickListener(v -> navigate(true));
    }

    private void navigate(boolean chat) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_OPEN_CHAT, chat));
        finish();
    }
}
