package com.team4.bintime.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.chat.PartnerSummary;
import com.team4.bintime.profile.BlockRepository;
import com.team4.bintime.profile.MockBlockRepository;
import com.team4.bintime.profile.MockProfileRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * BT-051 신고 제출 → 접수 완료(함께 차단) → 같은 매칭 재신고 안내. Mock 전용(FirebaseApp 제거)이라 운영 호출이 없다.
 * 실제 저장·중복·권한은 functions/emulator-tests/report.test.ts 가 검증한다.
 * 스크린샷: /sdcard/Android/data/com.team4.bintime/files/report-qa/
 */
@RunWith(AndroidJUnit4.class)
public class ReportDeviceTest {

    private static final String TARGET = "report-qa-target";
    private static final String MATCH = "c".repeat(64);
    private Context context;
    private MockBlockRepository blocks;

    private static final BlockRepository.Callback<Void> IGNORE = new BlockRepository.Callback<Void>() {
        @Override public void onSuccess(Void result) { }
        @Override public void onError(String message) { }
    };

    @Before
    public void mockOnly() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (FirebaseApp app : new ArrayList<>(FirebaseApp.getApps(context))) app.delete();
        assertFalse(AuthRepositories.isFirebaseEnabled(context));
        blocks = MockBlockRepository.shared(MockProfileRepository.shared());
        blocks.unblock(TARGET, IGNORE);
        MockReportRepository.shared(blocks).resetForTest();
    }

    @After
    public void restore() {
        blocks.unblock(TARGET, IGNORE);
        MockReportRepository.shared(blocks).resetForTest();
    }

    private ActivityScenario<ReportActivity> launch() {
        PartnerSummary partner = new PartnerSummary(TARGET, "김지민", "컴퓨터공학과 3학년 · 여성", false,
                "오늘 14:00 ~ 15:30", "1시간 30분 겹침");
        return ActivityScenario.launch(ReportActivity.intent(context, partner, MATCH));
    }

    @Test
    public void submitWithAlsoBlockThenReportAgain() throws Exception {
        try (ActivityScenario<ReportActivity> scenario = launch()) {
            idle();
            scenario.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.reportSubmit).isEnabled()); // 사유를 고르기 전에는 막힘
                assertTrue(((CheckBox) activity.findViewById(R.id.reportAlsoBlock)).isChecked());
                ((LinearLayout) activity.findViewById(R.id.reportReasons)).getChildAt(0).performClick();
                assertTrue(activity.findViewById(R.id.reportSubmit).isEnabled());
            });
            idle(); // 선택·버튼 활성화가 화면에 그려진 뒤 찍는다
            screenshot("01-form");
            scenario.onActivity(activity -> activity.findViewById(R.id.reportSubmit).performClick());
            idle();
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.reportForm).getVisibility());
                View state = activity.findViewById(R.id.reportState);
                assertEquals(activity.getString(R.string.report_done_title),
                        ((TextView) state.findViewById(R.id.stateTitle)).getText().toString());
                assertTrue(((TextView) state.findViewById(R.id.stateMessage)).getText().toString()
                        .contains(activity.getString(R.string.report_done_blocked)));
            });
            assertTrue(blocks.isBlocked(TARGET));
            screenshot("02-done-blocked");
        }
        // 같은 매칭을 다시 신고하면 '이미 신고' 안내
        try (ActivityScenario<ReportActivity> scenario = launch()) {
            idle();
            scenario.onActivity(activity -> {
                ((CheckBox) activity.findViewById(R.id.reportAlsoBlock)).setChecked(false);
                ((LinearLayout) activity.findViewById(R.id.reportReasons)).getChildAt(8).performClick();
                activity.findViewById(R.id.reportSubmit).performClick();
            });
            idle();
            scenario.onActivity(activity -> assertEquals(activity.getString(R.string.report_already_title),
                    ((TextView) activity.findViewById(R.id.reportState).findViewById(R.id.stateTitle)).getText().toString()));
            screenshot("03-already-reported");
        }
    }

    private static void idle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(900);
    }

    private void screenshot(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir = new File(context.getExternalFilesDir(null), "report-qa");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
