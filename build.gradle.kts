plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// The stamp lives in the app module: `count.pack` is a file next to its build script,
// so passing the root directory here always read 0. Resolving the module keeps the
// printed version equal to the one the APK carries.
tasks.register("printVersion") {
    val appDir = project(":app").projectDir
    val stamp = com.geno1024.ai.qqfm.gradle.Versioning.stamp(rootDir, appDir)
    doLast { println(stamp.versionName) }
}
