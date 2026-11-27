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
    private NotificationSettingsRepository repository;
    private com.google.firebase.firestore.ListenerRegistration registration;
    private final java.util.Map<String, MaterialSwitch> switches = new java.util.HashMap<>();
    private NotificationSettingsRepository.Snapshot snapshot;
    private com.team4.bintime.common.StateView stateView;
    private boolean binding, saving;
    private int observation;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_notification_settings);
        findViewById(R.id.notificationBack).setOnClickListener(v -> finish());
        NotificationPreferences prefs = new NotificationPreferences(this);
        LinearLayout rows = findViewById(R.id.settingRows);
        if (com.team4.bintime.auth.AuthRepositories.isFirebaseEnabled(this)) {
            repository = new NotificationSettingsRepository(this);
            stateView = new com.team4.bintime.common.StateView(this, null); rows.addView(stateView); stateView.showLoading();
        }
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
            switches.put(key, toggle);
            if (repository != null) toggle.setEnabled(false);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (repository == null) { prefs.set(key, checked); return; }
                if (binding || saving) return;
                saving = true; switches.values().forEach(view -> view.setEnabled(false));
                repository.set(key, checked, saved -> {
                    if (isFinishing() || isDestroyed()) return;
                    saving = false;
                    if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
                    if (!saved) { bindRemote(); android.widget.Toast.makeText(this, "설정을 저장하지 못했어요", android.widget.Toast.LENGTH_SHORT).show(); }
                    switches.values().forEach(view -> view.setEnabled(snapshot != null && repository.current()));
                });
            });
            rows.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        NotificationPermission.requestOnce(this);
    }
    @Override protected void onStart() { super.onStart(); if (repository != null) observeRemote(); }
    @Override protected void onStop() { observation++; if (registration != null) registration.remove(); registration = null; super.onStop(); }
    private void observeRemote() {
        if (registration != null) registration.remove();
        int visit = ++observation;
        registration = repository.observe(new NotificationSettingsRepository.Listener() {
            @Override public void onData(NotificationSettingsRepository.Snapshot loaded) {
                if (visit != observation || isFinishing() || isDestroyed()) return;
                snapshot = loaded; stateView.hide(); bindRemote();
            }
            @Override public void onError() {
                if (visit != observation || isFinishing() || isDestroyed()) return;
                switches.values().forEach(view -> view.setEnabled(false));
                stateView.showError("알림 설정을 불러오지 못했어요").primary("다시 시도", v -> observeRemote());
            }
        });
    }
    private void bindRemote() {
        if (snapshot == null) return;
        binding = true;
        switches.forEach((key, toggle) -> { toggle.setChecked(snapshot.enabled(key)); toggle.setEnabled(!saving && repository.current()); });
        binding = false;
    }
}
