package com.team4.bintime.common;

import android.app.Application;
import com.google.firebase.FirebaseApp;

/** Configure attestation before Activities/Repositories make their first Firebase request. */
public final class BinTimeApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
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
