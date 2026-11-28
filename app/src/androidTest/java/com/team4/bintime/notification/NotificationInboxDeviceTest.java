package com.team4.bintime.notification;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.functions.FirebaseFunctions;
import com.team4.bintime.BinTimeTestRunner;
import com.team4.bintime.FirebaseEmulatorTest;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Test process only: synthetic demo project, local Auth/Firestore, no production or actual FCM calls. */
@FirebaseEmulatorTest
@RunWith(AndroidJUnit4.class)
public class NotificationInboxDeviceTest {
    private static final String PROJECT = BinTimeTestRunner.EMULATOR_PROJECT;
    Context context;
    String uid;
    FirebaseFirestore db;
    @Before public void localOnly() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // 기본 앱은 BinTimeTestRunner 가 앱 시작 전에 demo-bintime 설정으로 만든다(delete()·재초기화 없음).
        BinTimeTestRunner.requireEmulatorMode();
        assertEquals(PROJECT, FirebaseApp.getInstance().getOptions().getProjectId());
        // 오케스트레이터가 테스트마다 앱 데이터를 지우므로(clearPackageData) 알림 권한도 초기화된다. 화면을 가리지 않게 먼저 허용한다.
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
        FirebaseAuth auth = FirebaseAuth.getInstance(); auth.useEmulator("10.0.2.2", 9099);
        uid = Tasks.await(auth.signInAnonymously(), 25, TimeUnit.SECONDS).getUser().getUid();
        db = FirebaseFirestore.getInstance();
        db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder().setHost("10.0.2.2:8080").setSslEnabled(false).setPersistenceEnabled(false).build());
        FirebaseFunctions.getInstance("asia-northeast3").useEmulator("10.0.2.2", 5001);
        seed("users/" + uid, new JSONObject().put("nickname", text("로컬 검증")));
    }
    static JSONObject text(String s) throws Exception { return new JSONObject().put("stringValue", s); }
    void seed(String path, JSONObject fields) throws Exception {
        // Owner token is accepted only by the explicitly addressed local emulator.
        HttpURLConnection conn = (HttpURLConnection) new URL("http://10.0.2.2:8080/v1/projects/" + PROJECT + "/databases/(default)/documents/" + path).openConnection();
        conn.setRequestMethod("PATCH"); conn.setRequestProperty("Authorization", "Bearer owner");
        conn.setRequestProperty("Content-Type", "application/json"); conn.setDoOutput(true);
        conn.getOutputStream().write(new JSONObject().put("fields", fields).toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(200, conn.getResponseCode()); conn.disconnect();
    }
    @Test public void ownerInboxRendersAndRequestTapMarksRead() throws Exception {
        String id = new String(new char[64]).replace('\0', 'a');
        seed("notifications/" + uid + "/items/" + id, new JSONObject().put("type", text("request"))
                .put("targetId", text("local-request")).put("title", text("새 매칭 요청이 도착했어요"))
                .put("createdAt", new JSONObject().put("timestampValue", "2026-11-24T04:00:00Z"))
                .put("readAt", new JSONObject().put("nullValue", JSONObject.NULL)));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(MainActivity::openNotifications);
            for (int i = 0; i < 40; i++) {
                Thread.sleep(250); final boolean[] ready = {false};
                scenario.onActivity(a -> ready[0] = ((LinearLayout) a.findViewById(R.id.notificationRows)).getChildCount() == 1);
                if (ready[0]) break;
            }
            scenario.onActivity(a -> {
                LinearLayout rows = a.findViewById(R.id.notificationRows); assertEquals(1, rows.getChildCount());
                assertEquals("새 매칭 요청이 도착했어요", ((TextView) rows.getChildAt(0).findViewById(R.id.notificationTitle)).getText().toString());
            });
            Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            File dir = new File(context.getExternalFilesDir(null), "notification-qa"); assertTrue(dir.isDirectory() || dir.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(dir, "01-inbox-local.png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, output); } image.recycle();
            scenario.onActivity(a -> ((LinearLayout) a.findViewById(R.id.notificationRows)).getChildAt(0).performClick());
            for (int i = 0; i < 30; i++) {
                if (Tasks.await(db.document("notifications/" + uid + "/items/" + id).get(), 5, TimeUnit.SECONDS).getTimestamp("readAt") != null) return;
                Thread.sleep(200);
            }
            fail("readAt was not persisted");
        }
    }
}
