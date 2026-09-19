package com.openminis.app.security

/**
 * Adapted from XINCODE-Public SecurityGate (GPL-3.0-or-later).
 * https://github.com/kusesad-1122/XINCODE-Public
 */
interface SecurityGate {
    fun setPermissionMode(mode: PermissionMode)
    fun getPermissionMode(): PermissionMode
    fun classify(toolName: String, toolArgs: String): GateCommand
    fun classifyRisk(command: String): RiskLevel
    fun decide(cmd: GateCommand, mode: PermissionMode): Decision
    fun preview(cmd: GateCommand): String
    fun audit(cmd: GateCommand, decision: Decision, result: String?)
    fun getAuditTrail(): List<AuditEntry>
    fun setPermissionRules(rules: List<PermissionRule>) {}
    fun setAuthorityProfile(profile: PermissionProfile?) {}
    fun verifyAuditChain(): AuditChainVerification = AuditChainVerification(ok = true)
}

data class AuditChainVerification(
    val ok: Boolean,
    val brokenAt: Int? = null,
)

data class AuditEntry(
    val timestamp: Long,
    val toolName: String,
    val toolArgs: String,
    val capability: Capability,
    val reversibility: Reversibility,
    val decision: String,
    val result: String?,
    val prevHash: String = "",
    val hash: String = "",
)
