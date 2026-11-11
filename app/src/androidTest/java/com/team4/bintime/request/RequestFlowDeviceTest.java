package com.team4.bintime.request;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.auth.MockAuthRepository;
import com.team4.bintime.common.FeedbackDialog;
import com.team4.bintime.matching.MatchingFragment;
import com.team4.bintime.matching.ProfileDetailActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.*;

/** Pixel 10 Mock UI 회귀. 실제 Firebase 앱을 테스트 프로세스에서 제거해 운영 호출을 막는다. */
@RunWith(AndroidJUnit4.class)
public class RequestFlowDeviceTest {
    private Context context;
    private String previousSuppression;

    @Before public void useMockOnly() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (FirebaseApp app : new ArrayList<>(FirebaseApp.getApps(context))) app.delete();
        assertFalse(AuthRepositories.isFirebaseEnabled(context));
        previousSuppression = context.getSharedPreferences("mockNotificationSettings", Context.MODE_PRIVATE)
                .getString("suppressedDate", "");
        new com.team4.bintime.notification.NotificationPreferences(context).suppressToday(true);
        MockAuthRepository auth = MockAuthRepository.shared();
        String email = "request.qa@kookmin.ac.kr";
        auth.sendSignInLink(email, null);
        auth.verifySignInLink(email, auth.getIssuedLinkForDemo(), null);
        assertTrue(AuthRepositories.get(context) instanceof MockAuthRepository);
        assertTrue(auth.isSignedIn());
        assertTrue(RequestRepositories.get(context).isMock());
    }

    @After public void restorePreferences() {
        context.getSharedPreferences("mockNotificationSettings", Context.MODE_PRIVATE)
                .edit().putString("suppressedDate", previousSuppression).commit();
        MockAuthRepository.shared().signOut();
    }

    @Test public void createFromProfileThenCancelAfterDialogRecreation() throws Exception {
        RequestRepository repository = RequestRepositories.get(context);
        try (ActivityScenario<ProfileDetailActivity> profile = ActivityScenario.launch(
                ProfileDetailActivity.intent(context, "u2", Collections.emptySet()))) {
            profile.onActivity(activity -> activity.findViewById(R.id.btnRequest).performClick());
            idle();
            profile.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.btnRequest).isEnabled());
                assertNotNull(activity.getSupportFragmentManager().findFragmentByTag("feedback"));
            });
            screenshot("01-request-created");
            profile.onActivity(activity -> ((FeedbackDialog) activity.getSupportFragmentManager()
                    .findFragmentByTag("feedback")).dismiss());
        }
        assertTrue(repository.hasPendingRequest("u2"));
        RequestRepository.Item original = repository.list(RequestRepository.Tab.SENT).stream()
                .filter(item -> "u2".equals(item.toUid)).findFirst().orElseThrow(AssertionError::new);
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity -> activity.findViewById(R.id.tabSent).performClick());
            idle(); screenshot("02-sent-list");
            main.onActivity(activity -> {
                LinearLayout cards = activity.findViewById(R.id.requestCards);
                boolean clicked = false;
                for (int i = 0; i < cards.getChildCount(); i++) {
                    TextView name = cards.getChildAt(i).findViewById(R.id.requestName);
                    if ("이서준".contentEquals(name.getText())) {
                        cards.getChildAt(i).findViewById(R.id.requestSecondary).performClick(); clicked = true; break;
                    }
                }
                assertTrue("새로 생성된 요청에도 취소 확인창이 연결돼야 한다", clicked);
            });
            idle(); screenshot("03-cancel-dialog");
            main.recreate(); idle();
            main.onActivity(activity -> {
                MatchingFragment matching = null;
                for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
                    if (fragment instanceof MatchingFragment) matching = (MatchingFragment) fragment;
                }
                assertNotNull(matching);
                DialogFragment dialog = (DialogFragment) matching.getChildFragmentManager().findFragmentByTag("requestAction");
                assertNotNull("회전 후 취소 확인창 복원", dialog);
                assertEquals("닫기", ((TextView) dialog.requireDialog().findViewById(R.id.dialogNegative)).getText().toString());
                assertEquals("요청취소", ((TextView) dialog.requireDialog().findViewById(R.id.dialogPositive)).getText().toString());
                dialog.requireDialog().findViewById(R.id.dialogPositive).performClick();
            });
            idle(); screenshot("04-after-cancel");
            main.onActivity(activity -> {
                RequestRepository restored = activity.requests();
                RequestRepository.Item canceled = restored.find(original.id);
                assertEquals(RequestRepository.Status.CANCELED, canceled.status());
                assertEquals(original.meetingDate, canceled.meetingDate);
                assertEquals(original.startMinute, canceled.startMinute);
                assertFalse(restored.hasPendingRequest("u2"));
                assertTrue(restored.respond("sent-1", RequestRepository.Status.CANCELED));
            });
            idle(); screenshot("05-empty-sent-list");
            main.onActivity(activity -> assertEquals(0, ((LinearLayout) activity.findViewById(R.id.requestCards)).getChildCount()));
        }
    }

    private static void idle() throws InterruptedException {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(300);
    }

    private void screenshot(String name) throws Exception {
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File directory = new File(context.getExternalFilesDir(null), "request-qa");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("screenshot directory");
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { image.recycle(); }
    }
}
