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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

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
        // Mock 모드는 BinTimeTestRunner 가 앱 시작 전에 고른다(FirebaseApp 을 만들지 않음). delete() 로 지우지 않는다.
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

    @Test public void cachedPhotoAppearsInAllRequestTabs() throws Exception {
        Bitmap photo = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        photo.eraseColor(android.graphics.Color.MAGENTA);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        photo.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        String[] path = {null};
        com.team4.bintime.profile.ProfileRepositories.get(context).uploadProfilePhoto("avatar-qa", bytes.toByteArray(),
                new com.team4.bintime.profile.ProfileRepository.Callback<String>() {
                    @Override public void onSuccess(String value) { path[0] = value; }
                    @Override public void onError(String message) { fail(message); }
                });
        assertNotNull(path[0]);
        MockRequestRepository local = new MockRequestRepository(AuthRepositories.get(context).getCurrentUid(), true);
        RequestRepository requests = (RequestRepository) java.lang.reflect.Proxy.newProxyInstance(
                RequestRepository.class.getClassLoader(), new Class<?>[]{RequestRepository.class},
                (proxy, method, args) -> method.getName().equals("isMock") ? false : method.invoke(local, args));
        RequestTabsController[] controller = {null};
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity -> {
                activity.findViewById(R.id.tabReceived).performClick();
                Fragment matching = activity.getSupportFragmentManager().getFragments().stream()
                        .filter(f -> f instanceof MatchingFragment).findFirst().orElseThrow(AssertionError::new);
                controller[0] = new RequestTabsController(matching, activity.findViewById(R.id.requestContent), requests,
                        intent -> fail("사진만 확인한다"), (uid, callback) -> {
                    com.team4.bintime.profile.UserProfile profile = com.team4.bintime.profile.UserProfile.empty();
                    profile.setUid(uid); profile.setNickname("사진 검증"); profile.setPhotoPath(path[0]);
                    callback.onSuccess(profile);
                });
            });
            for (RequestRepository.Tab tab : RequestRepository.Tab.values()) {
                main.onActivity(activity -> {
                    int tabId = tab == RequestRepository.Tab.SENT ? R.id.tabSent
                            : tab == RequestRepository.Tab.RECEIVED ? R.id.tabReceived : R.id.tabDone;
                    activity.findViewById(tabId).performClick();
                    controller[0].show(tab);
                    // 메인 루프를 넘기기 전에도 동기 반환된 사진이 채워져 있어야 한다.
                    LinearLayout cards = activity.findViewById(R.id.requestCards);
                    android.widget.ImageView avatar = cards.getChildAt(0).findViewById(R.id.requestAvatar);
                    assertTrue(avatar.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
                    Bitmap displayed = ((android.graphics.drawable.BitmapDrawable) avatar.getDrawable()).getBitmap();
                    assertEquals(android.graphics.Color.MAGENTA, displayed.getPixel(0, 0));
                });
                idle();
                main.onActivity(activity -> {
                    LinearLayout cards = activity.findViewById(R.id.requestCards);
                    assertTrue("카드가 있어야 한다: " + tab, cards.getChildCount() > 0);
                    android.widget.ImageView avatar = cards.getChildAt(0).findViewById(R.id.requestAvatar);
                    assertTrue(avatar.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
                    Bitmap displayed = ((android.graphics.drawable.BitmapDrawable) avatar.getDrawable()).getBitmap();
                    assertEquals(android.graphics.Color.MAGENTA, displayed.getPixel(0, 0));
                });
                screenshot("avatar-" + tab.name());
            }
        }
    }

    @Test public void profileReadRecoversAndRealDataDialogsDescribeTheirActions() throws Exception {
        // 실제 데이터 표시 분기만 선택하는 로컬 대역. Firebase/Functions에는 연결하지 않는다.
        MockRequestRepository local = new MockRequestRepository(AuthRepositories.get(context).getCurrentUid(), true);
        RequestRepository requests = (RequestRepository) java.lang.reflect.Proxy.newProxyInstance(
                RequestRepository.class.getClassLoader(), new Class<?>[]{RequestRepository.class},
                (proxy, method, args) -> method.getName().equals("isMock") ? false : method.invoke(local, args));
        int[] reads = {0};
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity -> {
                activity.findViewById(R.id.tabReceived).performClick();
                Fragment matching = activity.getSupportFragmentManager().getFragments().stream()
                        .filter(f -> f instanceof MatchingFragment).findFirst().orElseThrow(AssertionError::new);
                RequestTabsController controller = new RequestTabsController(matching,
                        activity.findViewById(R.id.requestContent), requests, intent -> fail("확인만 하고 수락하지 않는다"),
                        (uid, callback) -> {
                            if (++reads[0] == 1) callback.onError("temporary offline");
                            else {
                                com.team4.bintime.profile.UserProfile profile = com.team4.bintime.profile.UserProfile.empty();
                                profile.setUid(uid); profile.setNickname("복구된 프로필");
                                callback.onSuccess(profile);
                            }
                        });
                controller.show(RequestRepository.Tab.RECEIVED);
            });
            idle(); screenshot("06-profile-load-error");
            main.onActivity(activity -> {
                assertEquals(1, reads[0]);
                LinearLayout cards = activity.findViewById(R.id.requestCards);
                assertEquals("프로필을 불러오지 못했어요", ((TextView) cards.getChildAt(0).findViewById(R.id.requestName)).getText().toString());
                cards.getChildAt(0).findViewById(R.id.requestProfile).performClick();
            });
            idle(); screenshot("07-profile-recovered");
            main.onActivity(activity -> {
                assertEquals(2, reads[0]);
                LinearLayout cards = activity.findViewById(R.id.requestCards);
                assertEquals("복구된 프로필", ((TextView) cards.getChildAt(0).findViewById(R.id.requestName)).getText().toString());
                cards.getChildAt(0).findViewById(R.id.requestPrimary).performClick();
            });
            idle(); screenshot("08-accept-dialog");
            main.onActivity(activity -> {
                Fragment matching = activity.getSupportFragmentManager().getFragments().stream()
                        .filter(f -> f instanceof MatchingFragment).findFirst().orElseThrow(AssertionError::new);
                DialogFragment dialog = (DialogFragment) matching.getChildFragmentManager().findFragmentByTag("requestAction");
                String body = ((TextView) dialog.requireDialog().findViewById(R.id.dialogMessage)).getText().toString();
                assertTrue(body.contains("수락하면 이 시간으로 매칭이 확정돼요."));
                assertFalse(body.contains("취소하면"));
                dialog.requireDialog().findViewById(R.id.dialogNegative).performClick();
            });
            idle();
            main.onActivity(activity -> ((LinearLayout) activity.findViewById(R.id.requestCards)).getChildAt(0)
                    .findViewById(R.id.requestSecondary).performClick());
            idle(); screenshot("09-reject-dialog");
            main.onActivity(activity -> {
                Fragment matching = activity.getSupportFragmentManager().getFragments().stream()
                        .filter(f -> f instanceof MatchingFragment).findFirst().orElseThrow(AssertionError::new);
                DialogFragment dialog = (DialogFragment) matching.getChildFragmentManager().findFragmentByTag("requestAction");
                String body = ((TextView) dialog.requireDialog().findViewById(R.id.dialogMessage)).getText().toString();
                assertTrue(body.contains("거절하면 양쪽 요청 목록에서 사라져요."));
                assertFalse(body.contains("취소하면"));
            });
        }
    }

    @Test public void missingPublicProfileHasNoRetryAndPreservesRequest() throws Exception {
        MockRequestRepository local = new MockRequestRepository(AuthRepositories.get(context).getCurrentUid(), true);
        RequestRepository requests = (RequestRepository) java.lang.reflect.Proxy.newProxyInstance(
                RequestRepository.class.getClassLoader(), new Class<?>[]{RequestRepository.class},
                (proxy, method, args) -> method.getName().equals("isMock") ? false : method.invoke(local, args));
        int[] reads = {0};
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity -> {
                activity.findViewById(R.id.tabReceived).performClick();
                Fragment matching = activity.getSupportFragmentManager().getFragments().stream()
                        .filter(f -> f instanceof MatchingFragment).findFirst().orElseThrow(AssertionError::new);
                RequestTabsController controller = new RequestTabsController(matching, activity.findViewById(R.id.requestContent),
                        requests, intent -> fail("조회만 수행한다"), (uid, callback) -> { reads[0]++; callback.onSuccess(null); });
                controller.show(RequestRepository.Tab.RECEIVED); controller.render(); controller.render();
            });
            idle();
            main.onActivity(activity -> {
                assertEquals(1, reads[0]);
                LinearLayout cards = activity.findViewById(R.id.requestCards);
                assertEquals(1, cards.getChildCount());
                assertEquals(activity.getString(R.string.request_profile_missing), ((TextView) cards.getChildAt(0).findViewById(R.id.requestName)).getText().toString());
                assertEquals(android.view.View.GONE, cards.getChildAt(0).findViewById(R.id.requestProfile).getVisibility());
                RequestRepository.Item item = local.list(RequestRepository.Tab.RECEIVED).get(0);
                assertEquals(RequestRepository.Status.PENDING, item.status());
                assertTrue(((TextView) cards.getChildAt(0).findViewById(R.id.time)).getText().toString().contains("14:00 ~ 15:30"));
            });
            screenshot("10-profile-unavailable");
        }
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
            // 프로세스 복원 때 Mock 인증 메모리만 사라지는 상황을 재현한다.
            MockAuthRepository.shared().signOut();
            assertFalse(MockAuthRepository.shared().isSignedIn());
            main.recreate(); idle();
            main.onActivity(activity -> {
                assertEquals(original.fromUid, AuthRepositories.get(activity).getCurrentUid());
                assertTrue(AuthRepositories.get(activity).isSignedIn());
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

    @Test public void acceptReceivedRequestOpensConfirmedSnapshot() throws Exception {
        // 다른 테스트의 Mock 요청과 분리한다. 실제 Auth·메일은 사용하지 않는다.
        MockAuthRepository auth = MockAuthRepository.shared();
        auth.signOut();
        String email = "accept.qa@kookmin.ac.kr";
        auth.sendSignInLink(email, null);
        auth.verifySignInLink(email, auth.getIssuedLinkForDemo(), null);
        RequestRepository repository = RequestRepositories.get(context);
        assertTrue(repository.isMock());
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity -> {
                activity.findViewById(R.id.tabReceived).performClick();
                LinearLayout cards = activity.findViewById(R.id.requestCards);
                assertTrue(cards.getChildCount() > 0);
                cards.getChildAt(0).findViewById(R.id.requestPrimary).performClick();
            });
            idle();
            main.onActivity(activity -> {
                for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
                    if (fragment instanceof MatchingFragment) {
                        DialogFragment dialog = (DialogFragment) fragment.getChildFragmentManager().findFragmentByTag("requestAction");
                        assertNotNull(dialog);
                        dialog.requireDialog().findViewById(R.id.dialogPositive).performClick();
                    }
                }
            });
            idle();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                boolean found = false;
                for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof MatchAcceptedActivity) {
                        found = true;
                        assertTrue(((TextView) activity.findViewById(R.id.time)).getText().toString().contains("14:00"));
                    }
                }
                assertTrue("수락한 시간 스냅샷의 확인 화면으로 이동", found);
            });
            assertEquals(RequestRepository.Status.ACCEPTED, repository.find("received-1").status());
            Thread.sleep(1200);
            screenshot("06-request-accepted");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (android.app.Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))) {
                    if (activity instanceof MatchAcceptedActivity) activity.finish();
                }
            });
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
