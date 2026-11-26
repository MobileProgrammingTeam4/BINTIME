package com.team4.bintime.common;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

/** Debug SDK/token handling is excluded from release. Never log tokens in application code. */
final class AppCheckProviderInstaller {
    private AppCheckProviderInstaller() {}
    static void install(FirebaseApp app) {
        FirebaseAppCheck check = FirebaseAppCheck.getInstance(app);
        check.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance());
        check.setTokenAutoRefreshEnabled(true);
    }
}
