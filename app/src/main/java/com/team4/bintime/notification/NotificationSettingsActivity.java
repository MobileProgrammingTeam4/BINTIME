package com.team4.bintime.notification;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;

public class NotificationSettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_notification_settings);
        findViewById(R.id.notificationBack).setOnClickListener(v -> finish());
        NotificationPreferences prefs = new NotificationPreferences(this);
        LinearLayout rows = findViewById(R.id.settingRows);
        String[] labels = getResources().getStringArray(R.array.notification_settings_labels);
        for (int i = 0; i < labels.length; i++) {
            if (i == 0 || i == 6) {
                TextView header = new TextView(this);
                header.setText(i == 0 ? R.string.notification_settings : R.string.notification_popup_settings);
                header.setTextAppearance(R.style.TextAppearance_BinTime_H3);
                header.setPadding(0, ChipViews.dp(this, 24), 0, ChipViews.dp(this, 8));
                rows.addView(header);
            }
            MaterialSwitch toggle = new MaterialSwitch(this);
            toggle.setText(labels[i]);
            toggle.setTextAppearance(R.style.TextAppearance_BinTime_BodyM);
            toggle.setTrackTintList(androidx.core.content.ContextCompat.getColorStateList(this, R.color.switch_track));
            toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.bg_card)));
            toggle.setTrackDecorationTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
            toggle.setMinHeight(ChipViews.dp(this, 48));
            String key = NotificationPreferences.KEYS[i];
            toggle.setChecked(prefs.enabled(key));
            toggle.setOnCheckedChangeListener((button, checked) -> prefs.set(key, checked));
            rows.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        NotificationPermission.requestOnce(this);
    }
}
