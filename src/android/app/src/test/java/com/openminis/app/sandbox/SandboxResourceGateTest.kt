package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxResourceGateTest {
    @Test
    fun apkBuildDetectsGradleAndAapt() {
        assertTrue(SandboxResourceGate.isApkBuild("./gradlew :app:assembleDebug"))
        assertTrue(SandboxResourceGate.isApkBuild("gradle assembleRelease"))
        assertTrue(SandboxResourceGate.isApkBuild("/opt/android-sdk/build-tools/35.0.2/aapt2"))
        assertFalse(SandboxResourceGate.isApkBuild("python3 -m pytest"))
    }

    @Test
    fun packageManagerDetectsAptAndSdkmanager() {
        assertTrue(SandboxResourceGate.isPackageManager("apt-get install -y golang-go"))
        assertTrue(SandboxResourceGate.isPackageManager("sdkmanager --list"))
        assertTrue(SandboxResourceGate.isPackageManager("minis-android-sdk-setup"))
        assertFalse(SandboxResourceGate.isPackageManager("ls /tmp"))
    }
}
