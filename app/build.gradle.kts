import com.geno1024.ai.qqfm.gradle.Versioning
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val stamp = Versioning.stamp(rootDir, projectDir)

android {
    namespace = "com.geno1024.ai.qqfm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geno1024.ai.qqfm"
        minSdk = 26
        targetSdk = 37
        versionCode = stamp.versionCode
        versionName = stamp.versionName
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
    // that app, or the platform refuses it before the install even starts. The key is
    // supplied by the environment rather than kept in the repository, and a build
    // without one still works, it just cannot be installed over another one.
    //
    // GitHub Actions turns an unset secret into an empty variable rather than leaving
    // it unset, so blank values have to count as absent or this configuration fails.
    val signingKeyStore = providers.environmentVariable("QQFM_SIGNING_STORE_FILE")
        .orElse(providers.gradleProperty("qqfm.signing.storeFile"))
        .orNull
        ?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (signingKeyStore != null) {
            create("release") {
                storeFile = file(signingKeyStore)
                storePassword = providers.environmentVariable("QQFM_SIGNING_STORE_PASSWORD")
                    .orNull?.takeIf { it.isNotBlank() }
                keyAlias = providers.environmentVariable("QQFM_SIGNING_KEY_ALIAS")
                    .orNull?.takeIf { it.isNotBlank() } ?: "qqfm"
                keyPassword = providers.environmentVariable("QQFM_SIGNING_KEY_PASSWORD")
                    .orNull?.takeIf { it.isNotBlank() }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // The published build is the debug one, so the debug variant is the one that
            // has to be signed with the shared key; without it this falls back to the
            // machine-local debug key and no two builds can replace each other.
            if (signingKeyStore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
}

Versioning.bumpOnPackaging(project, projectDir, android = true)
