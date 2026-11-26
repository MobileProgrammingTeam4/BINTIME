package com.team4.bintime.common;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

/** Production attestation: no debug-provider classes or debug tokens in the release build. */
final class AppCheckProviderInstaller {
    private AppCheckProviderInstaller() {}
    static void install(FirebaseApp app) {
        FirebaseAppCheck check = FirebaseAppCheck.getInstance(app);
        check.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
        check.setTokenAutoRefreshEnabled(true);
    }
}
