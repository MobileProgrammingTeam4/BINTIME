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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 이메일 링크가 돌아오는 Firebase Hosting 기본 도메인 (App Link intent-filter host)
        manifestPlaceholders["authLinkHost"] =
            if (firebaseProjectId != null) "$firebaseProjectId.firebaseapp.com" else "auth.invalid"
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
