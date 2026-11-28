package com.team4.bintime.common;

import android.app.Application;
import com.google.firebase.FirebaseApp;

/** Configure attestation before Activities/Repositories make their first Firebase request. */
public final class BinTimeApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // debug 는 FirebaseInitProvider 를 빼고 여기서 만든다(기기 테스트가 앱 시작 전에 Mock/에뮬레이터를 고르게).
        // release 는 provider 가 이미 만들었으므로 init 이 아무것도 하지 않는다. App Check 는 그 뒤에 그대로 설치한다.
        FirebaseStartup.init(this);
        // Missing google-services.json keeps the existing Mock mode. Demo emulators never
        // mint real attestation/debug tokens or contact Play Integrity.
        for (FirebaseApp app : FirebaseApp.getApps(this)) {
            if (!FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())) continue;
            String project = app.getOptions().getProjectId();
            if (project == null || project.startsWith("demo-")) return;
            AppCheckProviderInstaller.install(app);
            return;
        }
    }
}
