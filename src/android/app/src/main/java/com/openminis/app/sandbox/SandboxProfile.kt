package com.openminis.app.sandbox

import android.content.Context
import android.content.SharedPreferences

sealed class SandboxProfile(val variant: String) {
    object Alpine : SandboxProfile("alpine") {
        val rootfsDirName = "alpine-rootfs"
        val prootBinaryName = "libproot.so"
        val defaultShell: String = "/bin/sh"
        val rootfsAsset: String = "alpine-minirootfs.tar.gz"
        val archMarker: String = ARCH
    }

    object Devstack : SandboxProfile("devstack") {
        val rootfsDirName = "devstack-rootfs"
        val prootBinaryName = "libproot.so"
        val defaultShell: String = "/bin/bash"
        val rootfsAsset: String = "ubuntu-noble-aarch64.tar.gz"
        val archMarker: String = "aarch64"
    }

    companion object {
        fun from(variant: String): SandboxProfile =
            when (variant) {
                Devstack.variant -> Devstack
                else -> Alpine
            }
    }
}

object SandboxSettings {
    private const val PREFS = "sandbox_settings"
    private const val KEY_VARIANT = "sandbox_variant"

    @Volatile
    private var cachedVariant: String = SandboxProfile.Alpine.variant

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prime(context: Context) {
        cachedVariant = prefs(context).getString(KEY_VARIANT, SandboxProfile.Alpine.variant)
            ?: SandboxProfile.Alpine.variant
    }

    fun currentProfile(): SandboxProfile =
        SandboxProfile.from(cachedVariant)

    fun setVariant(context: Context, variant: String) {
        cachedVariant = variant
        prefs(context).edit().putString(KEY_VARIANT, variant).apply()
    }
}
