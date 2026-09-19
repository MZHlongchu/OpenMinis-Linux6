package com.openminis.app.security

/**
 * Persistent allow/deny rule. Deny wins over allow.
 *
 * Adapted from XINCODE-Public PermissionRuleEntity (GPL-3.0-or-later).
 */
data class PermissionRule(
    val action: String,
    val toolFilter: String = "*",
    val pattern: String = "",
)
