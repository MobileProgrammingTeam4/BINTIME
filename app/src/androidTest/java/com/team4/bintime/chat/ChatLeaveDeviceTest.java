package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * BT-043 Mock 전용 화면 확인. 진행 중 방의 나가기 차단 안내와, 종료된 방을 나간 뒤 목록에서 사라지는 것을 본다.
 * 테스트 프로세스의 FirebaseApp 을 지워 운영 호출이 없다. 스크린샷은 앱 외부 파일 폴더의 leave-qa 에 저장한다.
 */
@RunWith(AndroidJUnit4.class)
public class ChatLeaveDeviceTest {
    private Context context;

    @Before public void mockOnly() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (FirebaseApp app : new ArrayList<>(FirebaseApp.getApps(context))) app.delete();
        assertFalse(AuthRepositories.isFirebaseEnabled(context));
    }

    @Test public void activeRoom_showsBlockedNotice_andKeepsRoom() throws Exception {
        // 옵션 메뉴는 화면만 남기고 닫는다(PopupWindow 를 뒤로 가기로 닫으면 Activity 가 끝날 수 있어 새로 띄운다).
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, "match-jimin"))) {
            showWindow(scenario);
            scenario.onActivity(activity -> activity.findViewById(R.id.chatMore).performClick());
            idle(); screenshot("01-active-options-menu");
        }
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, "match-jimin"))) {
            showWindow(scenario);
            scenario.onActivity(activity -> invoke(activity, "confirmLeaveRoom"));
            idle(); screenshot("02-active-leave-blocked");
            scenario.onActivity(activity -> {
                DialogFragment notice = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("chat.leaveBlocked");
                assertNotNull(notice);
                assertNull(activity.getSupportFragmentManager().findFragmentByTag("chat.leaveRoom"));
                assertTrue(texts(notice).contains(activity.getString(R.string.chat_leave_blocked_message)));
                notice.dismiss();
                assertFalse(activity.isFinishing());
            });
        }
    }

    @Test public void endedRoom_leave_hidesFromMyList() throws Exception {
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.moveToState(Lifecycle.State.RESUMED); idle();
            main.onActivity(activity -> activity.selectTab(R.id.nav_chat));
            idle(); screenshot("03-list-before-leave");
            main.onActivity(activity -> assertTrue(listTexts(activity).contains("서연")));
        }
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, "match-seoyeon"))) {
            showWindow(scenario);
            scenario.onActivity(activity -> invoke(activity, "confirmLeaveRoom"));
            idle(); screenshot("04-ended-leave-confirm");
            scenario.onActivity(activity -> {
                DialogFragment confirm = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("chat.leaveRoom");
                assertNotNull(confirm);
                confirm.requireDialog().findViewById(R.id.dialogPositive).performClick();
            });
            idle();
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.moveToState(Lifecycle.State.RESUMED); idle();
            main.onActivity(activity -> activity.selectTab(R.id.nav_chat));
            idle(); screenshot("05-list-after-leave");
            main.onActivity(activity -> assertFalse(listTexts(activity).contains("서연")));
        }
    }

    private static void invoke(Object target, String name) {
        try {
            Method method = ChatRoomActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String texts(DialogFragment dialog) {
        StringBuilder out = new StringBuilder();
        collect(dialog.requireDialog().getWindow().getDecorView(), out);
        return out.toString();
    }

    private static String listTexts(MainActivity activity) {
        StringBuilder out = new StringBuilder();
        RecyclerView list = activity.findViewById(R.id.chatRoomList);
        if (list != null) collect(list, out);
        return out.toString();
    }

    private static void collect(android.view.View view, StringBuilder out) {
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static void showWindow(ActivityScenario<ChatRoomActivity> scenario) throws Exception {
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        idle();
    }

    private static void idle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(900);
    }

    private void screenshot(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir = new File(context.getExternalFilesDir(null), "leave-qa");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
