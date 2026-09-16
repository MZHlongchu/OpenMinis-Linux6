package com.openminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.SandboxProfile
import com.openminis.app.sandbox.SandboxSettings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.ZipInputStream

class DevstackToolchainHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        if (argv.isEmpty() || argv[0] == "--help" || argv[0] == "-h" || argv[0] == "help") {
            return ok(HELP)
        }

        val profile = SandboxSettings.currentProfile()
        if (profile !is SandboxProfile.Devstack) {
            return errEnvelope("INVALID_VARIANT", "devstack-toolchain requires devstack variant (current: ${profile.variant})", request)
        }

        val sub = argv[0]
        val rest = argv.drop(1)

        return when (sub) {
            "install" -> handleInstall(rest, request)
            "list" -> handleList()
            "check" -> handleCheck()
            else -> NativeOffloadResult(2, "devstack-toolchain: unknown subcommand '$sub'.\n$HELP")
        }
    }

    private fun handleInstall(rest: List<String>, request: NativeOffloadRequest): NativeOffloadResult {
        val component = rest.firstOrNull() ?: "platform-tools"
        val rootfsDir = RootfsManager.getInstance(context).rootfsDir
        val installDir = File(rootfsDir, "opt/android-sdk")

        return try {
            val result = when (component) {
                "platform-tools", "pt" -> installPlatformTools(installDir)
                "jdk", "java" -> installJdk(installDir, rootfsDir)
                "gradle" -> installGradle(installDir)
                "nodejs", "node" -> installNodejs(rootfsDir)
                "all" -> {
                    val r1 = installPlatformTools(installDir)
                    val r2 = installJdk(installDir, rootfsDir)
                    val r3 = installGradle(installDir)
                    val r4 = installNodejs(rootfsDir)
                    val ok = r1.exitCode == 0 && r2.exitCode == 0 && r3.exitCode == 0 && r4.exitCode == 0
                    NativeOffloadResult(if (ok) 0 else 1,
                        "[platform-tools:${r1.exitCode}] [jdk:${r2.exitCode}] [gradle:${r3.exitCode}] [nodejs:${r4.exitCode}]\n")
                }
                else -> NativeOffloadResult(2, "devstack-toolchain: unknown component '$component'. Available: platform-tools, jdk, gradle, nodejs, all\n")
            }
            result
        } catch (t: Throwable) {
            errEnvelope("INSTALL_FAILED", t.message ?: "unknown", request)
        }
    }

    private fun installPlatformTools(installDir: File): NativeOffloadResult {
        val platformToolsDir = File(installDir, "platform-tools")
        if (platformToolsDir.exists() && File(platformToolsDir, "adb").exists()) {
            return ok("platform-tools already installed at $platformToolsDir\n")
        }

        val url = "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
        val zipFile = File(context.cacheDir, "platform-tools.zip")

        log_info("Downloading platform-tools from $url...")
        URL(url).openStream().use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }

        log_info("Extracting platform-tools...")
        extractZip(zipFile, installDir)

        zipFile.delete()
        return ok("platform-tools installed at $platformToolsDir\n")
    }

    private fun installJdk(installDir: File, rootfsDir: File): NativeOffloadResult {
        val jdkLink = File(rootfsDir, "usr/lib/jvm/default-java")
        if (jdkLink.exists()) {
            return ok("JDK already configured at $jdkLink\n")
        }

        if (!PRootKernel.isBooted) {
            return errEnvelope("PROOT_NOT_BOOTED", "PRoot kernel not booted. Start the sandbox first.", null)
        }

        val cmd = PRootKernel.buildProotCommand("apt-get update && apt-get install -y --no-install-recommends default-jdk")
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        return ok("JDK installed: exit=${proc.exitValue()}\n$output\n")
    }

    private fun installGradle(installDir: File): NativeOffloadResult {
        val gradleDir = File(installDir, "gradle")
        if (gradleDir.exists() && File(gradleDir, "bin/gradle").exists()) {
            return ok("gradle already installed at $gradleDir\n")
        }

        if (!PRootKernel.isBooted) {
            return errEnvelope("PROOT_NOT_BOOTED", "PRoot kernel not booted. Start the sandbox first.", null)
        }

        val cmd = PRootKernel.buildProotCommand("apt-get install -y --no-install-recommends gradle")
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        return ok("gradle installed: exit=${proc.exitValue()}\n$output\n")
    }

    private fun installNodejs(rootfsDir: File): NativeOffloadResult {
        if (!PRootKernel.isBooted) {
            return errEnvelope("PROOT_NOT_BOOTED", "PRoot kernel not booted. Start the sandbox first.", null)
        }

        val cmd = PRootKernel.buildProotCommand("apt-get install -y --no-install-recommends nodejs npm")
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        return ok("nodejs/npm installed: exit=${proc.exitValue()}\n$output\n")
    }

    private fun handleList(): NativeOffloadResult {
        val rootfsDir = RootfsManager.getInstance(context).rootfsDir
        val checks = listOf(
            "platform-tools/adb" to File(rootfsDir, "opt/android-sdk/platform-tools/adb"),
            "java" to File(rootfsDir, "usr/lib/jvm/default-java"),
            "gradle" to File(rootfsDir, "opt/android-sdk/gradle"),
            "node" to File(rootfsDir, "usr/bin/node"),
            "npm" to File(rootfsDir, "usr/bin/npm"),
        )
        val results = checks.map { (name, path) ->
            name to (if (path.exists()) "installed" else "missing")
        }
        val json = JSONObject().apply {
            put("variant", SandboxSettings.currentProfile().variant)
            put("components", JSONObject().apply {
                for ((name, status) in results) put(name, status)
            })
        }
        return okEnvelope(json)
    }

    private fun handleCheck(): NativeOffloadResult {
        val rootfsDir = RootfsManager.getInstance(context).rootfsDir
        val adb = File(rootfsDir, "opt/android-sdk/platform-tools/adb")
        val java = File(rootfsDir, "usr/lib/jvm/default-java")
        val gradle = File(rootfsDir, "opt/android-sdk/gradle")

        val missing = mutableListOf<String>()
        if (!adb.exists()) missing.add("platform-tools")
        if (!java.exists()) missing.add("jdk")
        if (!gradle.exists()) missing.add("gradle")

        return if (missing.isEmpty()) {
            ok("All toolchain components installed\n")
        } else {
            okEnvelope(JSONObject().apply {
                put("ok", false)
                put("error", JSONObject().apply {
                    put("code", "MISSING_COMPONENTS")
                    put("message", "Missing: ${missing.joinToString(", ")}")
                    put("missing", org.json.JSONArray(missing))
                })
            })
        }
    }

    private fun ok(body: String) = NativeOffloadResult(0, body)
    private fun okEnvelope(payload: Any): NativeOffloadResult {
        val obj = JSONObject().put("ok", true).put("data", payload)
        return NativeOffloadResult(0, obj.toString() + "\n")
    }

    private fun errEnvelope(code: String, message: String, request: NativeOffloadRequest): NativeOffloadResult {
        val obj = JSONObject().put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))
        return NativeOffloadResult(1, obj.toString() + "\n")
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(java.io.FileInputStream(zipFile).buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    java.io.FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        private const val TAG = "DevstackToolchain"
        private fun log_info(msg: String) { Log.i(TAG, msg) }
        private const val HELP = """devstack-toolchain — install Android SDK components inside devstack rootfs.

Usage:
  devstack-toolchain install [component]
    Install a component: platform-tools, jdk, gradle, nodejs, all.
  devstack-toolchain list
    List installed components.
  devstack-toolchain check
    Verify toolchain integrity.

Examples:
  devstack-toolchain install platform-tools
  devstack-toolchain install jdk
  devstack-toolchain install all
  devstack-toolchain list
"""
    }
}
