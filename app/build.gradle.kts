import com.geno1024.ai.qqfm.gradle.Versioning
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val stamp = Versioning.stamp(rootDir, projectDir)

// ---- Signing: the org's shared key, from the environment (CI) or local.properties ----
// Same names in every project, so no repository carries its own keystore settings.
val keystoreProps = Properties().apply {
    val local = File(rootProject.projectDir, "local.properties")
    if (local.exists()) local.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
val releaseStoreFile = secret("keystore.file", "KEYSTORE_STORE_FILE")
val releaseStorePassword = secret("keystore.storePassword", "KEYSTORE_STORE_PASSWORD")
val releaseKeyPassword = secret("keystore.keyPassword", "KEYSTORE_KEY_PASSWORD") ?: releaseStorePassword
val releaseKeyAlias = secret("keystore.keyAlias", "KEYSTORE_KEY_ALIAS") ?: "geno"

android {
    namespace = "com.geno1024.ai.qqfm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geno1024.ai.qqfm"
        minSdk = 26
        targetSdk = 37
        versionCode = stamp.versionCode
        versionName = stamp.versionName

        // ML Kit ships one native OCR pipeline per ABI; the phones this targets are
        // ARM, so dropping the x86 copies keeps ~23 MB out of every download.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // A build meant to replace an installed app has to carry the same signing key as
    // that app, or the platform refuses it before the install even starts. The key and
    // its passwords come from the environment (CI) or local.properties (never
    // committed); nothing about them is written here.
    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null) {
            create("release") {
                storeFile = File(releaseStoreFile)
                storePassword = releaseStorePassword
                keyPassword = releaseKeyPassword
                keyAlias = releaseKeyAlias
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // The published build is the debug one, so the debug variant is the one
            // that carries the shared key.
            if (releaseStoreFile != null && releaseStorePassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Bundled Chinese recognizer: the target device has no Play services, so the
    // model has to travel inside the APK rather than be downloaded on demand.
    implementation(libs.mlkit.text.recognition.chinese)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
}

Versioning.bumpOnPackaging(project, projectDir, android = true)
