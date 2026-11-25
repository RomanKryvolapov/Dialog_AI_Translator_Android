// A stand-in for a messenger call, to test call capture without a second person; never shipped with the app.
plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.romankryvolapov.callsim"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "com.romankryvolapov.callsim"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
