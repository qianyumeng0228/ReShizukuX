// ReShizukuX Xposed (LSPatch-style) support module.
//
// Phase 1 of the LSPatch/NPatch integration: the package engine ported from LSPatch
// (https://github.com/JingMatrix/LSPatch, GPL-3.0-or-later) compiles against minimal
// stub bindings for apkzlib / manifest-editor (axml). Actual APK re-packing, the loader
// payload and the manager UI are deliberately out of scope here -- this module must only
// build and be consumable by :manager.
//
// compileSdk = 37, minSdk = 24 and the Java 21 toolchain are applied to every
// com.android.* subproject from the root build.gradle, so they are not repeated here.
plugins {
    id("com.android.library")
    // Required for @Serializable on XposedRepoModule / repo DTOs (online LSPosed module repo).
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

android {
    namespace = "io.reshizukux.xposed"
}

dependencies {
    // Shizuku API (Binder/IPC surface already shipped by the app).
    implementation(project(":api"))

    // PatchConfig is (de)serialised to/from the patched apk as JSON.
    implementation("com.google.code.gson:gson:2.11.0")

    // FilenameUtils.getBaseName used when naming the output apk.
    implementation("commons-io:commons-io:2.16.1")

    // Online modules.lsposed.org repo JSON parsing (XposedRepoRepository).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Real APK re-packing engine: vendored com.android.tools.build.apkzlib (Phase 2 replaces
    // the old compile-only stubs). Pulls in Guava, apksig and bouncycastle transitively.
    implementation(project(":apkzlib"))
}
