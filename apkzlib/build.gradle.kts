// Vendored Google AGP APK-operations library (com.android.tools.build.apkzlib).
//
// Source: copy of https://github.com/JingMatrix/LSPatch/tree/master/apkzlib (which itself is a
// snapshot of com.android.tools.build:apkzlib, Apache-2.0). Brought in as a first-class Gradle
// module rather than hand-ported stubs, exactly like upstream LSPatch's :apkzlib.
//
// This is a plain JVM java-library: it is consumed (and dexed) by :xposed, which runs the patcher
// on-device. Dependency versions mirror apkzlib/build.gradle.kts in LSPatch v1.2.
plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    // The manager already bundles a newer org.bouncycastle (bcprov-jdk18on) — keep the old 1.70
    // artifacts compile-time only so D8 does not merge two bcprov copies into the APK.
    compileOnly("org.bouncycastle:bcpkix-jdk15on:1.70")
    compileOnly("org.bouncycastle:bcprov-jdk15on:1.70")
    api("com.google.guava:guava:32.0.1-jre")
    api("com.android.tools.build:apksig:8.0.2")

    // apkzlib uses Google AutoValue to generate value classes at compile time.
    compileOnlyApi("com.google.auto.value:auto-value-annotations:1.10.1")
    annotationProcessor("com.google.auto.value:auto-value:1.10.1")
}
