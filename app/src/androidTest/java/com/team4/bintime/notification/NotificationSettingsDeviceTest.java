package com.team4.bintime.notification;

import android.graphics.Bitmap;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.request.RequestRepository;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static com.team4.bintime.notification.NotificationInboxDeviceTest.text;

/** Local Functions RPC + real owner listeners on a visible Pixel. Does not call production. */
@RunWith(AndroidJUnit4.class)
public class NotificationSettingsDeviceTest {
    @Test public void saveRestoreSuppressPopupAndRejectThenSwitchAccount() throws Exception {
        NotificationInboxDeviceTest local = new NotificationInboxDeviceTest(); local.localOnly();
        FirebaseAuth auth = FirebaseAuth.getInstance(); auth.signOut();
        local.uid = Tasks.await(auth.signInWithEmailAndPassword("bt040-qa@school.ac.kr", "local-emulator-only"), 20, TimeUnit.SECONDS).getUser().getUid();
        local.seed("users/" + local.uid, new JSONObject().put("nickname", text("로컬 설정 검증")));
        local.seed("notificationSettings/" + local.uid, new JSONObject().put("availability",new JSONObject().put("booleanValue",false))
                .put("popup",new JSONObject().put("booleanValue",true)).put("suppressedDate",text("")));
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("pm grant com.team4.bintime android.permission.POST_NOTIFICATIONS").close();
        try (ActivityScenario<NotificationSettingsActivity> scenario = ActivityScenario.launch(NotificationSettingsActivity.class)) {
            waitForSwitch(scenario, "공강 시간 알림");
            scenario.onActivity(a -> find(a, "공강 시간 알림").performClick());
            for (int i=0;i<60;i++) {
                if (Boolean.TRUE.equals(Tasks.await(local.db.document("notificationSettings/"+local.uid).get(),5,TimeUnit.SECONDS).getBoolean("availability"))) break;
                Thread.sleep(250);
            }
            assertEquals(Boolean.TRUE, Tasks.await(local.db.document("notificationSettings/"+local.uid).get()).getBoolean("availability"));
            scenario.recreate(); waitForSwitch(scenario,"공강 시간 알림");
            scenario.onActivity(a -> assertTrue(find(a,"공강 시간 알림").isChecked()));
            capture(local,"01-settings-restored");
        }
        Map<String,Object> suppression = new HashMap<>(); suppression.put("key","suppressToday"); suppression.put("value",true);
        Tasks.await(FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("updateNotificationSettings").call(suppression),20,TimeUnit.SECONDS);
        Object policy=Tasks.await(FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("requestPopupPolicy").call(Collections.emptyMap()),20,TimeUnit.SECONDS).getData();
        assertEquals(Boolean.FALSE,((Map<?,?>)policy).get("allowed"));
        String id=new String(new char[64]).replace('\0','f');
        String date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1).toString();
        local.seed("publicProfiles/sender-local",new JSONObject().put("nickname",text("테스트 상대")).put("department",text("컴퓨터공학과"))
                .put("grade",text("3학년")).put("gender",text("여성")).put("bio",text("로컬 팝업 검증입니다")));
        local.seed("matchRequests/"+id,new JSONObject().put("fromUid",text("sender-local")).put("toUid",text(local.uid))
                .put("candidateId",text(local.uid)).put("status",text("PENDING")).put("purpose",text("스터디"))
                .put("meetingDate",text(date)).put("dayOfWeek",number(3)).put("startMinute",number(600)).put("endMinute",number(690))
                .put("overlapMinutes",number(90)).put("createdAt",new JSONObject().put("timestampValue","2026-11-24T00:00:00Z")));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            for(int i=0;i<60;i++){final boolean[] ready={false};scenario.onActivity(a->ready[0]=a.requests().find(id)!=null);if(ready[0])break;Thread.sleep(200);}
            scenario.onActivity(a->{
                assertNotNull(a.requests().find(id));
                androidx.fragment.app.Fragment matching=a.getSupportFragmentManager().findFragmentByTag("tab_"+R.id.nav_matching);
                assertNull(matching.getChildFragmentManager().findFragmentByTag(MatchRequestDialog.RESULT));
                // Manual opening is permitted even when today's automatic popup is suppressed.
                RequestPopupNavigator.open(a,id);
                RequestPopupNavigator.open(a,id); // repeated taps must still create one popup
            });Thread.sleep(1500);capture(local,"02-popup-local");
            // Force onStop before the async save callback can return to the main thread.
            // The same dialog must recover after completion while it is stopped.
            for(int i=0;i<60;i++){final boolean[] ready={false};scenario.onActivity(a->{
                androidx.fragment.app.Fragment matching=a.getSupportFragmentManager().findFragmentByTag("tab_"+R.id.nav_matching);
                androidx.fragment.app.DialogFragment popup=(androidx.fragment.app.DialogFragment)matching.getChildFragmentManager().findFragmentByTag(MatchRequestDialog.RESULT);
                ready[0]=popup.requireDialog().findViewById(R.id.popupToday).isEnabled();});if(ready[0])break;Thread.sleep(250);}
            scenario.onActivity(a->{
                androidx.fragment.app.Fragment matching=a.getSupportFragmentManager().findFragmentByTag("tab_"+R.id.nav_matching);
                androidx.fragment.app.DialogFragment popup=(androidx.fragment.app.DialogFragment)matching.getChildFragmentManager().findFragmentByTag(MatchRequestDialog.RESULT);
                android.widget.CheckBox today=popup.requireDialog().findViewById(R.id.popupToday);
                assertTrue(today.isEnabled());assertTrue(today.isChecked());today.performClick();popup.onStop();
            });
            for(int i=0;i<60;i++){if("".equals(Tasks.await(local.db.document("notificationSettings/"+local.uid).get(),5,TimeUnit.SECONDS).getString("suppressedDate")))break;Thread.sleep(250);}
            Thread.sleep(1500);InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{
                androidx.fragment.app.Fragment matching=a.getSupportFragmentManager().findFragmentByTag("tab_"+R.id.nav_matching);
                ((androidx.fragment.app.DialogFragment)matching.getChildFragmentManager().findFragmentByTag(MatchRequestDialog.RESULT)).onStart();
            });
            boolean recovered=false;
            for(int i=0;i<60;i++){final boolean[] ready={false};scenario.onActivity(a->{
                androidx.fragment.app.Fragment matching=a.getSupportFragmentManager().findFragmentByTag("tab_"+R.id.nav_matching);
                androidx.fragment.app.DialogFragment popup=(androidx.fragment.app.DialogFragment)matching.getChildFragmentManager().findFragmentByTag(MatchRequestDialog.RESULT);
                android.widget.CheckBox today=popup.requireDialog().findViewById(R.id.popupToday);
                ready[0]=today.isEnabled()&&!today.isChecked();});if(ready[0]){recovered=true;break;}Thread.sleep(250);}
            assertTrue("stopped save callback must release the checkbox when the popup resumes",recovered);
            scenario.onActivity(a->{androidx.fragment.app.Fragment matching=a.getSupportFragmentManager().findFragmentByTag("tab_"+R.id.nav_matching);
                androidx.fragment.app.DialogFragment popup=(androidx.fragment.app.DialogFragment)matching.getChildFragmentManager().findFragmentByTag(MatchRequestDialog.RESULT);
                assertNotNull(popup);assertTrue(popup.requireDialog().findViewById(R.id.popupReject).isEnabled());
                assertEquals(1,matching.getChildFragmentManager().getFragments().stream().filter(f->MatchRequestDialog.RESULT.equals(f.getTag())).count());
                popup.requireDialog().findViewById(R.id.popupReject).performClick();});
            for(int i=0;i<60;i++){if("REJECTED".equals(Tasks.await(local.db.document("matchRequests/"+id).get(),5,TimeUnit.SECONDS).getString("status")))break;Thread.sleep(250);}
            assertEquals("REJECTED",Tasks.await(local.db.document("matchRequests/"+id).get()).getString("status"));
        }
        auth.signOut(); Tasks.await(auth.signInAnonymously(),20,TimeUnit.SECONDS);
        try(ActivityScenario<NotificationSettingsActivity> other=ActivityScenario.launch(NotificationSettingsActivity.class)){
            waitForSwitch(other,"공강 시간 알림");other.onActivity(a->assertFalse(find(a,"공강 시간 알림").isChecked()));
        }
    }
    private static JSONObject number(int n)throws Exception{return new JSONObject().put("integerValue",String.valueOf(n));}
    private static MaterialSwitch find(NotificationSettingsActivity a,String label){LinearLayout rows=a.findViewById(R.id.settingRows);for(int i=0;i<rows.getChildCount();i++)if(rows.getChildAt(i)instanceof MaterialSwitch){MaterialSwitch s=(MaterialSwitch)rows.getChildAt(i);if(label.contentEquals(s.getText()))return s;}throw new AssertionError("switch missing");}
    private static void waitForSwitch(ActivityScenario<NotificationSettingsActivity>s,String label)throws Exception{for(int i=0;i<60;i++){final boolean[] ready={false};s.onActivity(a->ready[0]=find(a,label).isEnabled());if(ready[0])return;Thread.sleep(250);}fail("settings not loaded");}
    private static void capture(NotificationInboxDeviceTest local,String name)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();File dir=new File(local.context.getExternalFilesDir(null),"notification-qa");assertTrue(dir.isDirectory()||dir.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
}
