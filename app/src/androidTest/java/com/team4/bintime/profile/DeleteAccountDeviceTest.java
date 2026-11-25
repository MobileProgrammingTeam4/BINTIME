package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.FirebaseApp;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.auth.MockAuthRepository;
import com.team4.bintime.auth.OnboardingActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;

/**
 * BT-045 마이 → 회원 탈퇴 → 확인 → 온보딩. Mock 전용(FirebaseApp 제거)이라 운영 계정·데이터에 영향이 없다.
 * 실제 서버 정리는 functions/emulator-tests/delete-account.test.ts 가 검증한다.
 * 스크린샷: /sdcard/Android/data/com.team4.bintime/files/delete-qa/
 */
@RunWith(AndroidJUnit4.class)
public class DeleteAccountDeviceTest {

    private static final String EMAIL = "delete.qa@kookmin.ac.kr";
    private Context context;

    @Before
    public void mockOnly() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (FirebaseApp app : new ArrayList<>(FirebaseApp.getApps(context))) app.delete();
        assertFalse(AuthRepositories.isFirebaseEnabled(context));
        previousSuppression = context.getSharedPreferences("mockNotificationSettings", Context.MODE_PRIVATE)
                .getString("suppressedDate", "");
        // Mock 받은 요청 팝업이 확인창을 가리지 않게 오늘 팝업을 숨긴다(RequestFlowDeviceTest 와 같음).
        new com.team4.bintime.notification.NotificationPreferences(context).suppressToday(true);
        MockAuthRepository auth = MockAuthRepository.shared();
        auth.sendSignInLink(EMAIL, null);
        auth.verifySignInLink(EMAIL, auth.getIssuedLinkForDemo(), null);
        assertTrue(auth.isSignedIn());
    }

    private String previousSuppression;

    @After
    public void signOut() {
        context.getSharedPreferences("mockNotificationSettings", Context.MODE_PRIVATE)
                .edit().putString("suppressedDate", previousSuppression).commit();
        MockAuthRepository.shared().signOut();
    }

    @Test
    public void confirmDeletesAccountAndReturnsToOnboarding() throws Exception {
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity -> ((BottomNavigationView) activity.findViewById(R.id.bottomNav)).setSelectedItemId(R.id.nav_my));
            idle();
            main.onActivity(activity -> activity.findViewById(R.id.menuDeleteAccount).performClick());
            idle();
            main.onActivity(activity -> {
                DialogFragment confirm = findDialog(activity);
                assertNotNull(confirm);
                assertEquals(activity.getString(R.string.my_delete_message),
                        ((TextView) confirm.requireDialog().findViewById(R.id.dialogMessage)).getText().toString());
            });
            screenshot("01-confirm");
            main.onActivity(activity -> findDialog(activity).requireDialog().findViewById(R.id.dialogPositive).performClick());
            idle();
            assertFalse(MockAuthRepository.shared().isSignedIn());
            assertFalse(MockAuthRepository.shared().isRegisteredEmail(EMAIL)); // 같은 이메일로 다시 가입할 수 있다
            Activity[] resumed = {null};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> list = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
                if (!list.isEmpty()) resumed[0] = list.iterator().next();
            });
            assertTrue(resumed[0] instanceof OnboardingActivity);
            screenshot("02-onboarding-after-delete");
        }
    }

    private static DialogFragment findDialog(MainActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (!(fragment instanceof MyPageFragment)) continue;
            for (Fragment child : fragment.getChildFragmentManager().getFragments()) {
                if (child instanceof DialogFragment) return (DialogFragment) child;
            }
        }
        return null;
    }

    private static void idle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(900);
    }

    private void screenshot(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir = new File(context.getExternalFilesDir(null), "delete-qa");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
