plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.romankryvolapov.dialogaitranslator"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "com.romankryvolapov.dialogaitranslator"
        minSdk = 33 // the system recogniser takes our audio from Android 13 on
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += listOf(
            "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/io.netty.versions.properties", "META-INF/AL2.0", "META-INF/LGPL2.1",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.datastore)
    implementation(libs.datastore.tink)
    implementation(libs.tink.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
