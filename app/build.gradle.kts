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
        versionCode = 2
        versionName = "0.5.0"
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
            signingConfig = if (haveReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
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
    implementation(libs.vico.compose.m3)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
