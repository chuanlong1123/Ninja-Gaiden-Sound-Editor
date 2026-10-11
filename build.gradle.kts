plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ngsoundeditor.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ngsoundeditor.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 38
        versionName = "3.8.0-prototype"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
