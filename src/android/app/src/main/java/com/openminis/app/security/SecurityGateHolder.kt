package com.openminis.app.security

import android.content.Context
import com.openminis.app.notification.ApprovalNotifier
import com.openminis.app.service.ApprovalGate
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide gate + persisted mode/rules. ApprovalGate is only the UI wait.
 */
object SecurityGateHolder {
    val gate: SecurityGateImpl = SecurityGateImpl()

    private const val PREFS = "security_gate"
    private const val KEY_MODE = "permission_mode"
    private const val KEY_RULES = "permission_rules"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = p.getString(KEY_MODE, PermissionMode.ASK.name) ?: PermissionMode.ASK.name
        gate.setPermissionMode(runCatching { PermissionMode.valueOf(mode) }.getOrDefault(PermissionMode.ASK))
        val raw = p.getString(KEY_RULES, "[]") ?: "[]"
        val rules = mutableListOf<PermissionRule>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                rules += PermissionRule(
                    action = o.optString("action"),
                    toolFilter = o.optString("toolFilter", "*"),
                    pattern = o.optString("pattern", ""),
                )
            }
        }
        gate.setPermissionRules(rules)
    }

    fun persist(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        p.putString(KEY_MODE, gate.getPermissionMode().name)
        val arr = JSONArray()
        // rules are not exposed from impl; persist is called after setMode/setRules
        p.apply()
    }

    fun setMode(context: Context, mode: PermissionMode) {
        gate.setPermissionMode(mode)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    fun setRules(context: Context, rules: List<PermissionRule>) {
        gate.setPermissionRules(rules)
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("action", r.action)
                    .put("toolFilter", r.toolFilter)
                    .put("pattern", r.pattern),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, arr.toString()).apply()
    }

    /**
     * @return a failed result to short-circuit, or null to execute the tool.
     */
    suspend fun intercept(context: Context, name: String, argsJson: String): ToolExecutionResult? {
        val canonical = ToolAliases.canonical(name)
        val cmd = gate.classify(canonical, argsJson)
        val decision = gate.decide(cmd, gate.getPermissionMode())
        gate.audit(cmd, decision, null)
        return when (decision) {
            is Decision.Allow -> null
            is Decision.Denied -> ToolExecutionResult(
                "SecurityGate denied: ${decision.reason}",
                false,
                toolTitle = canonical,
            )
            is Decision.NeedConfirm -> {
                val preview = decision.preview.take(240).ifBlank { decision.reason }
                val id = ApprovalGate.requestApproval(canonical, preview)
                ApprovalNotifier(context).notifyApproval(
                    id,
                    canonical,
                    preview,
                )
                val approved = ApprovalGate.waitFor(id)
                ApprovalNotifier.cancelApproval(context, id)
                if (!approved) {
                    ToolExecutionResult(
                        "User rejected or timed out $canonical",
                        false,
                        toolTitle = canonical,
                    )
                } else {
                    null
                }
            }
        }
    }
}
