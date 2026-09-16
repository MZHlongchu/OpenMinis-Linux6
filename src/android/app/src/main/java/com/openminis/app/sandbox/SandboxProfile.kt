package com.openminis.app.sandbox

import android.content.Context
import android.content.SharedPreferences

sealed class SandboxProfile(val variant: String) {
    abstract val rootfsDirName: String
    abstract val prootBinaryName: String
    abstract val defaultShell: String
    abstract val rootfsAsset: String
    abstract val archMarker: String

    object Alpine : SandboxProfile("alpine") {
        override val rootfsDirName = "alpine-rootfs"
        override val prootBinaryName = "libproot.so"
        override val defaultShell: String = "/bin/sh"
        override val rootfsAsset: String = "alpine-minirootfs.tar.gz"
        override val archMarker: String = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.lowercase() ?: "aarch64"
    }

    object Devstack : SandboxProfile("devstack") {
        override val rootfsDirName = "devstack-rootfs"
        override val prootBinaryName = "libproot.so"
        override val defaultShell: String = "/bin/bash"
        override val rootfsAsset: String = "ubuntu-noble-aarch64.tar.gz"
        override val archMarker: String = "aarch64"
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
    private var cachedVariant: String = SandboxProfile.Devstack.variant

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prime(context: Context) {
        cachedVariant = prefs(context).getString(KEY_VARIANT, SandboxProfile.Devstack.variant)
            ?: SandboxProfile.Devstack.variant
    }

    fun currentProfile(): SandboxProfile =
        SandboxProfile.from(cachedVariant)

    fun setVariant(context: Context, variant: String) {
        cachedVariant = variant
        prefs(context).edit().putString(KEY_VARIANT, variant).apply()
    }
}
