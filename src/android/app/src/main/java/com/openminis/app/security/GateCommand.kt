package com.openminis.app.security

/**
 * Adapted from XINCODE-Public GateCommand / Decision (GPL-3.0-or-later).
 * https://github.com/kusesad-1122/XINCODE-Public
 */
data class GateCommand(
    val toolName: String,
    val toolArgs: String,
    val capability: Capability,
    val reversibility: Reversibility,
    val why: String,
)

sealed class Decision {
    data class Allow(val reason: String) : Decision()
    data class NeedConfirm(val reason: String, val preview: String) : Decision()
    data class Denied(val reason: String) : Decision()
}
