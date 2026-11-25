// The dialog itself — context, prompts, methods, scheduler, questions, the AI providers — without Android types.
plugins {
    id("org.jetbrains.kotlin.jvm") // its version comes from the root classpath entry
    alias(libs.plugins.kotlin.serialization)
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.slf4j.api)

    // Chat models: the same library the PC app uses. Its default HTTP client needs java.net.http, which Android
    // does not have, hence OkHttp; every model is given that builder explicitly (CloudRepositoryImpl).
    implementation(libs.langchain4j.openai) { exclude(module = "langchain4j-http-client-jdk") }
    implementation(libs.langchain4j.gemini) { exclude(module = "langchain4j-http-client-jdk") }
    implementation(libs.langchain4j.anthropic) { exclude(module = "langchain4j-http-client-jdk") }
    implementation(libs.langchain4j.okhttp) { exclude(module = "okhttp-jvm") }
    // Both AI libraries get this one OkHttp, in its Android variant inside the app.
    implementation(libs.okhttp)

    // Realtime transcription (Gemini Live), which LangChain4j does not cover. Conscrypt's desktop build has no
    // native code for Android, and Android has its own TLS provider.
    implementation(libs.google.genai) { exclude(group = "org.conscrypt") }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnitPlatform() }
