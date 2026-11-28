package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.request.MatchAppointment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

/**
 * BT-034 준비 안 된 방의 재시도 화면. Mock 전용(FirebaseApp 없음, BinTimeTestRunner)이라 운영 호출이 없다.
 * 실제 ensureMatchChat 실패 코드 처리는 ChatLinkRetryTest(JVM)가 검증한다.
 */
@RunWith(AndroidJUnit4.class)
public class ChatLinkStateDeviceTest {
    private static final String ROOM = "match-jimin";
    private Context context;
    private List<MatchAppointment> appointments;

    @Before
    public void mockOnly() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Mock 모드는 BinTimeTestRunner 가 앱 시작 전에 고른다(FirebaseApp 을 만들지 않음). delete() 로 지우지 않는다.
        assertFalse(AuthRepositories.isFirebaseEnabled(context));
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        int minute = now.getHour() * 60 + now.getMinute();
        appointments = Collections.singletonList(new MatchAppointment("link-qa", ROOM, now.toLocalDate().toString(),
                Math.max(0, minute - 10), Math.min(1440, minute + 60), "스터디", MatchAppointment.Status.ACTIVE, null, false));
    }

    @After
    public void restore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                MockChatRepository.shared().setAppointmentsForDemo(ROOM, appointments, false, true));
    }

    @Test
    public void notReadyRoomShowsPreparingStateWithRetryAndHidesWhenReady() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                MockChatRepository.shared().setAppointmentsForDemo(ROOM, appointments, false, false));
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, ROOM))) {
            showWindow(scenario);
            scenario.onActivity(activity -> {
                View state = activity.findViewById(R.id.chatRoomState);
                assertEquals(View.VISIBLE, state.getVisibility());
                assertEquals(View.INVISIBLE, activity.findViewById(R.id.chatMessageList).getVisibility());
                assertEquals(activity.getString(R.string.chat_link_preparing_title),
                        ((TextView) state.findViewById(R.id.stateTitle)).getText().toString());
                TextView retry = state.findViewById(R.id.statePrimary);
                assertEquals(activity.getString(R.string.chat_retry), retry.getText().toString());
                assertTrue(retry.isEnabled());
                assertFalse(activity.findViewById(R.id.chatSend).isEnabled());
                // Mock 은 동기 콜백으로 준비 완료를 알린다. 요청 중 상태에 갇히지 않고 버튼이 다시 눌려야 한다.
                retry.performClick();
                retry.performClick();
                assertTrue(state.findViewById(R.id.statePrimary).isEnabled());
            });
            screenshot("01-room-preparing-retry");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    MockChatRepository.shared().setAppointmentsForDemo(ROOM, appointments, false, true));
            idle();
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.chatRoomState).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.chatMessageList).getVisibility());
                assertTrue(activity.findViewById(R.id.chatInput).isEnabled());
            });
            screenshot("02-room-ready");
        }
    }

    @Test
    public void archivedRoomDoesNotShowRetry() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                MockChatRepository.shared().setAppointmentsForDemo(ROOM, appointments, true));
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, ROOM))) {
            showWindow(scenario);
            scenario.onActivity(activity -> assertEquals(View.GONE, activity.findViewById(R.id.chatRoomState).getVisibility()));
        }
    }

    private static void showWindow(ActivityScenario<ChatRoomActivity> scenario) throws Exception {
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        idle();
    }

    private static void idle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(900);
    }

    private void screenshot(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir = new File(context.getExternalFilesDir(null), "chat-link-qa");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
