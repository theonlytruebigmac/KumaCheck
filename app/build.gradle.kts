import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.kumacheck"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kumacheck"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.0"
        // C4: instrumented test scaffolding. AndroidJUnitRunner is the
        // standard runner; tests live in `app/src/androidTest/`. We don't
        // wire connected-device runs into the default CI workflow (the
        // emulator boot adds ~30s to every PR for a single smoke test);
        // instead, an opt-in workflow / manual `./gradlew :app:connectedDebugAndroidTest`
        // exercises the suite when a real device is attached.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing — credentials come from either a non-checked-in
    // `keystore.properties` at the repo root (local builds) or environment
    // variables (CI). Both forms accept the same four fields:
    //
    //     storeFile / RELEASE_KEYSTORE_FILE         path to the .keystore
    //     storePassword / RELEASE_KEYSTORE_STORE_PASSWORD
    //     keyAlias / RELEASE_KEYSTORE_KEY_ALIAS
    //     keyPassword / RELEASE_KEYSTORE_KEY_PASSWORD
    //
    // Env vars win over the properties file when both are present, so CI can
    // override a developer's local file without touching it.
    //
    // When neither is configured we fall back to the Android debug keystore so
    // `assembleRelease` still completes locally — the resulting APK is signed
    // with a throwaway debug key and must not be published.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = if (keystorePropsFile.exists()) {
        Properties().apply { FileInputStream(keystorePropsFile).use { load(it) } }
    } else null
    fun signingValue(envName: String, propName: String): String? =
        System.getenv(envName)?.takeIf { it.isNotBlank() }
            ?: keystoreProps?.getProperty(propName)?.takeIf { it.isNotBlank() }

    val storeFilePath = signingValue("RELEASE_KEYSTORE_FILE", "storeFile")
    val storePassword = signingValue("RELEASE_KEYSTORE_STORE_PASSWORD", "storePassword")
    val keyAlias = signingValue("RELEASE_KEYSTORE_KEY_ALIAS", "keyAlias")
    val keyPassword = signingValue("RELEASE_KEYSTORE_KEY_PASSWORD", "keyPassword")
    val haveReleaseKey = storeFilePath != null && storePassword != null &&
        keyAlias != null && keyPassword != null
    if (haveReleaseKey) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(storeFilePath!!)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (haveReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // C1: fall back to debug signing only for local QA, AND
                // suffix the applicationId so the artifact is obviously
                // not shippable. Without the suffix, a misconfigured CI
                // run would produce an APK that *looks* like the real
                // release (same package name) but is signed with a
                // throwaway debug key, breaking Play Store updates and
                // confusing users who sideload the wrong build.
                signingConfig = signingConfigs.getByName("debug")
                applicationIdSuffix = ".unsigned"
                versionNameSuffix = "-unsigned"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    // C2: align the compile-time toolchain with the bytecode target so the
    // compiler can't accept JDK-21 stdlib references that would fail at
    // runtime on a Pixel. The daemon stays on JDK 21 (see
    // gradle/gradle-daemon-jvm.properties) — that only affects Gradle's
    // own JVM, not how `app/` is compiled.
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    // KS2: ProcessLifecycleOwner so KumaCheckApp can pause the socket's
    // reconnect loop when the app is fully backgrounded AND notifications
    // are disabled (no MonitorService keeping the socket alive).
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)
    implementation(libs.socketio)
    // KS3: use OkHttp explicitly for our REST helper (status page detail).
    // It's already transitive via socket.io-client; making it a first-class
    // dependency lets us call into the API with named coordinates instead
    // of relying on the transitive surface.
    implementation(libs.okhttp)
    implementation(libs.vico.compose.m3)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // CI3: JUnit 5 platform. Jupiter API + engine for new tests; Vintage
    // engine keeps the existing JUnit 4 suite running so the migration
    // is incremental rather than big-bang. `useJUnitPlatform()` below
    // routes both engines through the platform.
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)

    // C4: instrumented (on-device / emulator) test scaffolding. The smoke
    // test in `app/src/androidTest/` confirms the package context loads —
    // sufficient to keep the build path warm. Real test suites live
    // alongside it as features need them.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

// CI3: route :app:testDebugUnitTest through the JUnit 5 platform. Vintage
// engine surfaces the existing JUnit 4 tests; Jupiter engine surfaces any
// new test written against `org.junit.jupiter.api.Test`.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
