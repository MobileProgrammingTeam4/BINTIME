package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.chat.ChatRoom;
import com.team4.bintime.chat.ChatRoomActivity;
import com.team4.bintime.chat.MockChatRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * BT-046 차단 목록·해제와 채팅 차단 표시 (Figma 117:1906). Mock 전용(FirebaseApp 제거)이라 운영 호출이 없다.
 * 실제 차단·검색 제외·전송 거부는 functions/emulator-tests/block.test.ts 가 검증한다.
 * 스크린샷: /sdcard/Android/data/com.team4.bintime/files/block-qa/
 */
@RunWith(AndroidJUnit4.class)
public class BlockedUsersDeviceTest {

    private static final String ROOM = "match-jimin";
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
        MockProfileRepository profiles = MockProfileRepository.shared();
        profiles.putPublicProfile(profile("qa-junho", "박준호", "전자공학과", "3학년"));
        profiles.putPublicProfile(profile("qa-yubin", "이유빈", "소프트웨어학과", "2학년"));
        blocks = MockBlockRepository.shared(profiles);
        blocks.unblock("qa-junho", IGNORE);
        blocks.unblock("qa-yubin", IGNORE);
    }

    @After
    public void restore() {
        blocks.unblock("qa-junho", IGNORE);
        blocks.unblock("qa-yubin", IGNORE);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                MockChatRepository.shared().setBlockStateForDemo(ROOM, ChatRoom.BlockState.NONE));
    }

    private static UserProfile profile(String uid, String name, String department, String grade) {
        UserProfile p = UserProfile.empty();
        p.setUid(uid);
        p.setNickname(name);
        p.setDepartment(department);
        p.setGrade(grade);
        p.setGender("남성");
        return p;
    }

    @Test
    public void listShowsBlockedUsersAndUnblockRemovesRow() throws Exception {
        // Figma 순서(박준호 위)와 같게 최근 차단이 위로 온다.
        blocks.block("qa-yubin", IGNORE);
        blocks.block("qa-junho", IGNORE);
        try (ActivityScenario<BlockedUsersActivity> scenario =
                     ActivityScenario.launch(new Intent(context, BlockedUsersActivity.class))) {
            idle();
            scenario.onActivity(activity -> {
                assertEquals("차단한 사용자 2명", ((TextView) activity.findViewById(R.id.blockedCount)).getText().toString());
                LinearLayout list = activity.findViewById(R.id.blockedList);
                assertEquals(2, list.getChildCount());
                assertEquals("박준호", ((TextView) list.getChildAt(0).findViewById(R.id.blockedName)).getText().toString());
                assertEquals("전자공학과 3학년 · 남성",
                        ((TextView) list.getChildAt(0).findViewById(R.id.blockedInfo)).getText().toString());
                assertEquals(View.GONE, activity.findViewById(R.id.blockedState).getVisibility());
            });
            screenshot("01-blocked-list");

            scenario.onActivity(activity -> {
                LinearLayout list = activity.findViewById(R.id.blockedList);
                list.getChildAt(0).findViewById(R.id.blockedUnblock).performClick();
                assertEquals(1, list.getChildCount());
                assertEquals("차단한 사용자 1명", ((TextView) activity.findViewById(R.id.blockedCount)).getText().toString());
            });
            assertFalse(blocks.isBlocked("qa-junho"));
            assertTrue(blocks.isBlocked("qa-yubin"));
            idle();
            screenshot("02-after-unblock");

            scenario.onActivity(activity -> ((LinearLayout) activity.findViewById(R.id.blockedList))
                    .getChildAt(0).findViewById(R.id.blockedUnblock).performClick());
            idle();
            scenario.onActivity(activity -> {
                assertEquals(0, ((LinearLayout) activity.findViewById(R.id.blockedList)).getChildCount());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.blockedState).getVisibility());
            });
            screenshot("03-empty");
        }
    }

    @Test
    public void blockedChatKeepsHistoryButDisablesInput() throws Exception {
        for (ChatRoom.BlockState state : new ChatRoom.BlockState[]{ChatRoom.BlockState.BY_ME, ChatRoom.BlockState.BY_PARTNER}) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    MockChatRepository.shared().setBlockStateForDemo(ROOM, state));
            try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, ROOM))) {
                idle();
                scenario.onActivity(activity -> {
                    EditText input = activity.findViewById(R.id.chatInput);
                    assertFalse(input.isEnabled());
                    assertFalse(activity.findViewById(R.id.chatSend).isEnabled());
                    assertEquals(View.GONE, activity.findViewById(R.id.chatAiRow).getVisibility());
                    assertEquals(activity.getString(state == ChatRoom.BlockState.BY_ME
                            ? R.string.chat_blocked_by_me : R.string.chat_blocked_by_partner), input.getHint().toString());
                    assertTrue(((androidx.recyclerview.widget.RecyclerView) activity.findViewById(R.id.chatMessageList))
                            .getAdapter().getItemCount() > 0); // 기록은 그대로 보인다
                });
                screenshot(state == ChatRoom.BlockState.BY_ME ? "04-chat-blocked-by-me" : "05-chat-blocked-by-partner");
            }
        }
        // 해제되면 다시 보낼 수 있다.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                MockChatRepository.shared().setBlockStateForDemo(ROOM, ChatRoom.BlockState.NONE));
        try (ActivityScenario<ChatRoomActivity> scenario = ActivityScenario.launch(ChatRoomActivity.intent(context, ROOM))) {
            idle();
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.chatInput).isEnabled());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.chatAiRow).getVisibility());
            });
        }
    }

    private static void idle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(900);
    }

    private void screenshot(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir = new File(context.getExternalFilesDir(null), "block-qa");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
