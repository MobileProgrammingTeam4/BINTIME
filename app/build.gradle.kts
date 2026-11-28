plugins {
    alias(libs.plugins.android.application)
}

// google-services.json은 팀 정책상 Git에 올리지 않는다.
// 파일이 있는 로컬 환경에서만 Firebase 플러그인을 적용하고, 없으면(CI 등) Mock 모드로 빌드한다.
val googleServicesFile = file("google-services.json")
val firebaseProjectId: String? = if (googleServicesFile.exists()) {
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(googleServicesFile) as Map<String, Any?>
    (json["project_info"] as? Map<String, Any?>)?.get("project_id") as? String
} else {
    null
}
if (googleServicesFile.exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android { 
    namespace = "com.team4.bintime"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "com.team4.bintime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-week1"
        // 기기 테스트: 앱 시작 전에 Mock/로컬 에뮬레이터 모드를 고르는 runner(androidTest/BinTimeTestRunner).
        // 오케스트레이터로 테스트마다 새 프로세스를 띄우고 앱 데이터를 지운다. FirebaseApp.delete() 로 모드를 바꾸지 않는다.
        testInstrumentationRunner = "com.team4.bintime.BinTimeTestRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // 이메일 링크가 돌아오는 Firebase Hosting 기본 도메인 (App Link intent-filter host)
        manifestPlaceholders["authLinkHost"] =
            if (firebaseProjectId != null) "$firebaseProjectId.firebaseapp.com" else "auth.invalid"
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-encoding", "UTF-8"))
}
dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.database)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.appcheck)
    debugImplementation(libs.firebase.appcheck.debug)
    releaseImplementation(libs.firebase.appcheck.playintegrity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // 테스트 전용(앱 APK 에 포함되지 않음): 기기 테스트를 테스트마다 별도 프로세스로 실행
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
}
