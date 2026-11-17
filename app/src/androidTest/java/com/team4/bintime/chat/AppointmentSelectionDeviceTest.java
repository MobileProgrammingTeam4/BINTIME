package com.team4.bintime.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.DialogFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.firebase.FirebaseApp;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.request.MatchAppointment;
import com.team4.bintime.request.MatchAppointmentPolicy;
import com.team4.bintime.request.MatchSelectionSheet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** 보이는 Pixel 10, Mock 전용. 선택 창·회전·개별 종료·읽기 전용 표현을 검증한다. */
@RunWith(AndroidJUnit4.class)
public class AppointmentSelectionDeviceTest {
    private Context context;
    private List<MatchAppointment> appointments;
    @Before public void mockOnly() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (FirebaseApp app : new ArrayList<>(FirebaseApp.getApps(context))) app.delete();
        assertFalse(AuthRepositories.isFirebaseEnabled(context));
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        int minute = now.getHour() * 60 + now.getMinute(); String today = now.toLocalDate().toString();
        appointments = Arrays.asList(new MatchAppointment("current-qa", "match-jimin", today, Math.max(0, minute - 15), Math.min(1440, minute + 60),
                "스터디", MatchAppointment.Status.ACTIVE, null, false),
                new MatchAppointment("future-qa", "match-jimin", now.toLocalDate().plusDays(1).toString(), 780, 870, "점심 식사", MatchAppointment.Status.ACTIVE, null, false),
                new MatchAppointment("review-qa", "match-jimin", now.toLocalDate().minusDays(1).toString(), 840, 930, "함께 공부하기", MatchAppointment.Status.ENDED, true, false),
                new MatchAppointment("written-qa", "match-jimin", now.toLocalDate().minusDays(2).toString(), 600, 690, "가벼운 대화", MatchAppointment.Status.ENDED, true, true),
                new MatchAppointment("early-qa", "match-jimin", now.toLocalDate().plusDays(2).toString(), 600, 690, "제외할 조기 종료", MatchAppointment.Status.ENDED, false, false));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> MockChatRepository.shared().setAppointmentsForDemo("match-jimin", appointments, false));
    }
    @Test public void selectFutureThenConfirmOnlyThatAppointmentEndsAfterRecreation() throws Exception {
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, "match-jimin"))) {
            idle(); scenario.onActivity(activity -> {
                assertTrue(((TextView) activity.findViewById(R.id.chatMatchTime)).getText().toString().contains("외 1건"));
                assertTrue(MatchSelectionSheet.show(activity.getSupportFragmentManager(), "chat.selectEnd", MatchAppointmentPolicy.Action.END, appointments, System.currentTimeMillis()));
            }); idle(); screenshot("01-end-selection");
            scenario.recreate(); idle();
            scenario.onActivity(activity -> {
                DialogFragment sheet = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("chat.selectEnd");
                assertNotNull(sheet); LinearLayout rows = sheet.requireView().findViewById(R.id.matchSelectionRows);
                assertEquals(2, rows.getChildCount()); rows.getChildAt(1).performClick();
            }); idle(); screenshot("02-selected-end-confirm");
            scenario.onActivity(activity -> {
                DialogFragment confirm = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("chat.endMatch");
                assertNotNull(confirm); confirm.requireDialog().findViewById(R.id.dialogPositive).performClick();
            }); idle();
            scenario.onActivity(activity -> {
                assertFalse(((TextView) activity.findViewById(R.id.chatMatchTime)).getText().toString().contains("외 1건"));
                assertTrue(activity.findViewById(R.id.chatInput).isShown());
                // 후기 선택 창은 제공 API를 직접 호출한다. 실제 저장은 BT-044 범위다.
                assertTrue(MatchSelectionSheet.show(activity.getSupportFragmentManager(), "qa.review", MatchAppointmentPolicy.Action.REVIEW, appointments, System.currentTimeMillis()));
            }); idle(); screenshot("03-review-selection");
            scenario.onActivity(activity -> {
                DialogFragment sheet = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("qa.review");
                assertEquals(2, ((LinearLayout) sheet.requireView().findViewById(R.id.matchSelectionRows)).getChildCount()); sheet.dismiss();
            });
        }
    }
    @Test public void archivedConversationShowsRecordOnlyAndHidesInput() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> MockChatRepository.shared().setAppointmentsForDemo("match-jimin", appointments, true));
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, "match-jimin"))) {
            idle(); scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.chatInput).isShown());
                assertFalse(activity.findViewById(R.id.chatInput).isEnabled());
                assertEquals(activity.getString(R.string.chat_archive_read_only), ((TextView) activity.findViewById(R.id.chatInput)).getHint().toString());
                assertFalse(activity.findViewById(R.id.chatSend).isEnabled());
                assertFalse(activity.findViewById(R.id.chatMatchInfo).isShown());
            }); screenshot("04-archived-conversation");
        }
    }
    private static void idle() throws Exception { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(900); }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File dir = new File(context.getExternalFilesDir(null), "appointment-qa"); assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        bitmap.recycle();
    }
}
