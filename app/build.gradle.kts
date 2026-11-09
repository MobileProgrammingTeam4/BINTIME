plugins {
    alias(libs.plugins.android.application)
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
    testImplementation(libs.junit)
}
