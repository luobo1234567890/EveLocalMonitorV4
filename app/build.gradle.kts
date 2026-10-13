plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.eve.localmonitor"; compileSdk = 35
    defaultConfig { applicationId = "com.eve.localmonitor"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "4.0" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}
