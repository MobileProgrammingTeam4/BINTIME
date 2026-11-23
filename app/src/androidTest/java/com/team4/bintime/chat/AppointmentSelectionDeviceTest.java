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
            showWindow(scenario); scenario.onActivity(activity -> {
                assertTrue(((TextView) activity.findViewById(R.id.chatMatchTime)).getText().toString().contains("외 1건"));
                assertTrue(MatchSelectionSheet.show(activity.getSupportFragmentManager(), "chat.selectEnd", MatchAppointmentPolicy.Action.END, appointments, System.currentTimeMillis()));
            }); idle(); screenshot("01-end-selection");
            scenario.recreate(); idle();
            scenario.onActivity(activity -> {
                DialogFragment sheet = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("chat.selectEnd");
                assertNotNull(sheet); LinearLayout rows = sheet.requireView().findViewById(R.id.matchSelectionRows);
                assertEquals(2, rows.getChildCount());
                assertNotNull(rows.getChildAt(0).findViewById(R.id.appointmentTimeMatch));
                assertEquals(android.view.View.GONE, rows.getChildAt(0).findViewById(R.id.timeOverlapDivider).getVisibility());
                rows.getChildAt(1).performClick();
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
            showWindow(scenario); scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.chatInput).isShown());
                assertFalse(activity.findViewById(R.id.chatInput).isEnabled());
                assertEquals(activity.getString(R.string.chat_archive_read_only), ((TextView) activity.findViewById(R.id.chatInput)).getHint().toString());
                assertFalse(activity.findViewById(R.id.chatSend).isEnabled());
                assertFalse(activity.findViewById(R.id.chatMatchInfo).isShown());
                assertTrue(activity.findViewById(R.id.chatEndedBanner).isShown());
                assertEquals(activity.getString(R.string.chat_review_write), ((TextView) activity.findViewById(R.id.chatReviewLink)).getText().toString());
                androidx.recyclerview.widget.RecyclerView messages = activity.findViewById(R.id.chatMessageList);
                androidx.recyclerview.widget.RecyclerView.ViewHolder last = messages.findViewHolderForAdapterPosition(messages.getAdapter().getItemCount() - 1);
                assertNotNull(last);
                assertTrue(last.itemView.findViewById(R.id.chatPartnerLeftNotice).isShown());
            }); screenshot("04-archived-conversation");
        }
    }
    @Test public void roomPreparationDisablesComposerUntilReadyWithoutLosingDraft() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> MockChatRepository.shared().setAppointmentsForDemo("match-jimin", appointments, false, false));
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, "match-jimin"))) {
            showWindow(scenario); screenshot("05-chat-preparing"); scenario.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.chatInput).isEnabled());
                assertFalse(activity.findViewById(R.id.chatSend).isEnabled());
                assertFalse(activity.findViewById(R.id.chatAiReply).isEnabled());
                TextView input = activity.findViewById(R.id.chatInput);
                assertEquals(activity.getString(R.string.chat_link_preparing), input.getHint().toString());
                input.setText("준비 후 보낼 초안");
                MockChatRepository.shared().setAppointmentsForDemo("match-jimin", appointments, false, true);
            }); idle(); screenshot("05-chat-ready");
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.chatInput).isEnabled());
                assertTrue(activity.findViewById(R.id.chatSend).isEnabled());
                assertTrue(activity.findViewById(R.id.chatAiReply).isEnabled());
                assertEquals("준비 후 보낼 초안", ((TextView) activity.findViewById(R.id.chatInput)).getText().toString());
            });
        }
    }
    @Test public void archivedRoomShowsViewOnlyAfterBothAppointmentReviewsAreWritten() throws Exception {
        String first = "review-one-" + java.util.UUID.randomUUID(), second = "review-two-" + java.util.UUID.randomUUID();
        String day = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString();
        List<MatchAppointment> two = Arrays.asList(
                new MatchAppointment(first, "match-jimin", day, 600, 690, "스터디", MatchAppointment.Status.ENDED, true, false),
                new MatchAppointment(second, "match-jimin", day, 720, 810, "스터디", MatchAppointment.Status.ENDED, true, false));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            writeReview(first); MockChatRepository.shared().setAppointmentsForDemo("match-jimin", two, true);
        });
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, "match-jimin"))) {
            showWindow(scenario); scenario.onActivity(activity -> assertEquals(activity.getString(R.string.chat_review_write),
                    ((TextView) activity.findViewById(R.id.chatReviewLink)).getText().toString()));
            screenshot("06-reviews-incomplete");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> writeReview(second));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); idle();
            scenario.onActivity(activity -> assertEquals(activity.getString(R.string.chat_review_view),
                    ((TextView) activity.findViewById(R.id.chatReviewLink)).getText().toString()));
            screenshot("07-reviews-complete");
        }
    }
    /** BT-035 결정 A: 종료 시각이 지났는데 서버가 아직 ACTIVE 로 두면 마지막 약속 카드와 0:00:00 을 보여 준다. */
    @Test public void overdueActiveKeepsLastCardWithZeroRemaining() throws Exception {
        String yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString();
        List<MatchAppointment> overdue = Arrays.asList(
                new MatchAppointment("overdue-early-qa", "match-jimin", yesterday, 540, 600, "스터디", MatchAppointment.Status.ACTIVE, null, false),
                new MatchAppointment("overdue-late-qa", "match-jimin", yesterday, 600, 690, "스터디", MatchAppointment.Status.ACTIVE, null, false),
                new MatchAppointment("ended-later-qa", "match-jimin", yesterday, 900, 960, "스터디", MatchAppointment.Status.ENDED, true, false));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> MockChatRepository.shared().setAppointmentsForDemo("match-jimin", overdue, false));
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, "match-jimin"))) {
            showWindow(scenario);
            scenario.onActivity(activity -> assertOverdueCard(activity));
            screenshot("08-overdue-zero-remaining");
            Thread.sleep(1500); // 1초 갱신을 지나도 카드가 사라지거나 음수로 바뀌지 않는다
            scenario.onActivity(activity -> assertOverdueCard(activity));
            scenario.recreate(); idle(); // 회전·재생성 뒤에도 같은 표시
            scenario.onActivity(activity -> assertOverdueCard(activity));
        }
    }
    private static void assertOverdueCard(ChatRoomActivity activity) {
        assertTrue(activity.findViewById(R.id.chatMatchInfo).isShown());
        assertFalse("서버가 ENDED 로 바꾸기 전에는 종료 배너를 띄우지 않는다", activity.findViewById(R.id.chatEndedBanner).isShown());
        assertEquals(activity.getString(R.string.chat_yesterday) + " 10:00 ~ 11:30", ((TextView) activity.findViewById(R.id.chatMatchTime)).getText().toString());
        assertEquals(activity.getString(R.string.chat_remaining, "0:00:00"), ((TextView) activity.findViewById(R.id.chatMatchRemaining)).getText().toString());
        assertTrue("지연 구간에도 입력은 그대로", activity.findViewById(R.id.chatInput).isEnabled());
    }
    private static void writeReview(String id) {
        com.team4.bintime.review.MockReviewRepository.shared().submitReview(id, "u1", com.team4.bintime.review.Satisfaction.GREAT,
                java.util.Collections.emptySet(), null, new com.team4.bintime.review.ReviewRepository.SubmitCallback() {
                    @Override public void onSuccess() {}
                    @Override public void onError() { fail("로컬 후기 작성 실패"); }
                });
    }
    private static void showWindow(ActivityScenario<ChatRoomActivity> scenario) throws Exception {
        // 비런처 Activity 직접 시작 시 AVD가 빈 전환 표면을 유지하는 경우 정상 화면 복귀와 같은 수명주기를 거친다.
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        idle();
    }
    private static void idle() throws Exception { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(900); }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = null;
        // AVD 전환 프레임이 검게 촬영되면 실제 화면이 그려질 때까지 짧게 기다린다.
        for (int attempt = 0; attempt < 12; attempt++) {
            bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(bitmap);
            int visible = 0;
            for (int x = 1; x <= 3; x++) for (int y = 1; y <= 3; y++) {
                int pixel = bitmap.getPixel(bitmap.getWidth() * x / 4, bitmap.getHeight() * y / 4);
                if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180 && android.graphics.Color.blue(pixel) > 180) visible++;
            }
            if (visible >= 3) break;
            bitmap.recycle(); bitmap = null; Thread.sleep(500);
        }
        assertNotNull("검은 전환 화면은 UI 증거로 저장하지 않는다", bitmap);
        File dir = new File(context.getExternalFilesDir(null), "appointment-qa"); assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        bitmap.recycle();
    }
}
