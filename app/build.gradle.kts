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

// Like the PC app, which reads the `.env` in its folder: with every `installDebug` the keys in the project's `.env`,
// and the user's own texts in `personal/` (facts about the user, the topic of the calls, any Questions prompt), go to
// the phone. They land in the app's private folder; the app takes them into its encrypted settings at its next start
// and deletes them. Only a debug build lets `run-as` write there, and the APK itself carries none of it.
val envFile = rootProject.file(".env")
val personalDir = rootProject.file("personal")
val adb = androidComponents.sdkComponents.adb
val packageName = android.defaultConfig.applicationId
tasks.matching { it.name == "installDebug" }.configureEach {
    doLast {
        val sent = buildList {
            if (envFile.isFile) add(envFile to "files/import.env")
            personalDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name }
                ?.forEach { add(it to "files/import-personal/${it.name}") }
        }
        if (sent.isEmpty()) return@doLast logger.lifecycle("No .env or personal/ in the project folder, nothing sent to the phone")
        val adbPath = adb.get().asFile.absolutePath
        val serials = System.getenv("ANDROID_SERIAL")?.let(::listOf)
            ?: ProcessBuilder(adbPath, "devices").start().inputStream.bufferedReader().readLines()
                .drop(1).map { it.split('\t') }.filter { it.getOrNull(1) == "device" }.map { it[0] }
        for (serial in serials) {
            for ((file, target) in sent) {
                val push = ProcessBuilder(
                    adbPath, "-s", serial, "shell",
                    "run-as $packageName sh -c 'umask 077 && mkdir -p ${target.substringBeforeLast('/')} && cat > $target'",
                ).redirectInput(file).redirectErrorStream(true).start()
                val output = push.inputStream.bufferedReader().readText().trim()
                if (push.waitFor() != 0 || output.isNotEmpty()) {
                    throw GradleException("${file.name} did not reach $serial: $output")
                }
            }
            logger.lifecycle("Sent to $serial: ${sent.joinToString { it.first.name }}; the app takes them in at its next start")
        }
    }
}
