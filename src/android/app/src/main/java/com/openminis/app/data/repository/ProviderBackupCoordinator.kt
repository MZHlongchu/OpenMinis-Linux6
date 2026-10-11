package com.openminis.app.data.repository

import android.content.Context
import android.util.Base64
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.backup.ProviderOverridesWire
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

// [T-android-overrides-wire-codec] overrides 层的编解码（含 modality 位域与
// MODALITY_BIT_* 布局）已挪进 backup/BackupRecordMapper.kt 的 ProviderOverridesWire：
// 本文件顶在架构棘轮基线上（.architecture-baseline.json，只许下调），而那段逻辑
// 是纯 org.json 映射、与数据库无关，抽出去既能给这里腾空间，也能被单测直接驱动。

internal class ProviderBackupCoordinator(
    private val context: Context,
    private val host: Host,
) {
    interface Host {
        val configValue: ProviderConfig
        fun saveConfig(config: ProviderConfig)
        fun workingCopy(): ProviderConfig
        fun ensureConfigLoaded()
        fun addInstance(instance: ProviderInstance)
        fun instance(id: String): ProviderInstance?
        fun visibleEntries(instanceId: String): List<ModelEntry>
        val configLock: Any
        val providerDao: ProviderConfigDao
        fun loadApiKey(instanceId: String): String?
        fun saveApiKey(instanceId: String, key: String)
        fun loadAllThinkingRulesIntoCache()
    }

    // -- Import / Export --

    /**
     * [T-android-provider-export-oauth-token] OAuth manager for [instance],
     * covering EVERY OAuth provider type — including gemini / antigravity, which
     * OAuthManager.forInstance deliberately omits (it's tuned for the
     * login/logout/manual-bearer UI paths). Used only by the export/import
     * round-trip so we don't widen forInstance's shared behavior.
     */
    private fun oauthManagerFor(
        instance: ProviderInstance,
    ): com.openminis.app.auth.OAuthManager? = when (instance.providerType) {
        ProviderType.anthropic -> com.openminis.app.auth.ClaudeOAuthManager(context, instance.id)
        ProviderType.openAI -> com.openminis.app.auth.OpenAIOAuthManager(context, instance.id)
        ProviderType.xAI -> com.openminis.app.auth.XAIOAuthManager(context, instance.id)
        ProviderType.gemini -> com.openminis.app.auth.GeminiOAuthManager(context, instance.id)
        ProviderType.kimiCode -> com.openminis.app.auth.KimiOAuthManager(context, instance.id)
        else -> null
    }

    /** Export an instance as shareable JSON (includes base64-encoded API key). */
    fun exportInstanceJSON(instanceId: String): String? {
        host.ensureConfigLoaded()
        val instance = host.instance(instanceId) ?: return null
        val entries = host.visibleEntries(instanceId) + host.configValue.modelEntries.filter {
            it.providerInstanceId == instanceId && it.isHidden
        }

        val obj = JSONObject().apply {
            put("providerType", instance.providerType.name)
            put("label", instance.label)
            put("credentialType", instance.credentialType.name)
            val modelsArr = JSONArray()
            for (entry in entries) {
                modelsArr.put(JSONObject().apply {
                    put("modelId", entry.baseModel.id)
                    put("displayName", entry.baseModel.displayName)
                    put("isHidden", entry.isHidden)
                    if (entry.isCustom) put("isCustom", true)
                    entry.baseModel.contextWindow?.let { put("contextWindow", it) }
                    entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
                    entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
                    // [T-provider-export-model-overrides] 整个 ModelOverrides 层
                    // （含 1.14 新增的 maxThinkingLevel / topP / customHeaders /
                    // extraBodyParams）统一交给 ProviderOverridesWire.encode ——
                    // 逐字段手写正是这些键被漏掉的原因：层里加了字段，这条手写
                    // 清单不会报错，只是静默不写，分享/往返一次就丢。每个键仍然
                    // 增量 + 可选：老构建忽略不认识的键，导入侧逐键独立读。
                    if (!entry.overrides.isEmpty) {
                        put("overrides", ProviderOverridesWire.encode(entry.overrides))
                    }
                    // Mirror iOS export of baseModel.modalityOverride — when
                    // the base model itself carries explicit modality info,
                    // serialize an interop bitfield so iOS can faithfully
                    // restore it. Android's native baseModel uses string
                    // lists too; this is purely additive for iOS readers.
                    run {
                        val bf = ProviderOverridesWire.bitfieldFromLists(
                            entry.baseModel.inputModalities,
                            entry.baseModel.outputModalities,
                        )
                        if (bf != 0) put("modalityOverride", bf)
                    }
                    entry.baseModel.inputModalities?.let {
                        put("inputModalities", JSONArray(it))
                    }
                    entry.baseModel.outputModalities?.let {
                        put("outputModalities", JSONArray(it))
                    }
                })
            }
            put("models", modelsArr)
            host.loadApiKey(instanceId)?.let { key ->
                put("apiKey", Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
            }
            // Export manual OAuth bearer token (mirrors iOS `manualOAuthToken` key).
            // Stored per-instance via OAuthManager; only present for OAuth providers
            // where the user pasted a static token via the Manual Bearer Token UI.
            run {
                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val manual = mgr?.loadManualBearerToken()
                if (!manual.isNullOrEmpty()) {
                    put("manualOAuthToken", Base64.encodeToString(manual.toByteArray(), Base64.NO_WRAP))
                }
            }
            // [T-android-provider-export-oauth-token] (XIN 38955) Export the
            // STRUCTURED OAuth-login credential (access_token / refresh_token /
            // expire_at) saved by the OAuth login flow under a separate pref than
            // apiKey / manualOAuthToken. Previously omitted, so an OAuth-logged-in
            // Claude / OpenAI / Gemini / xAI provider exported with no usable
            // credential and imported as not-authenticated. The whole token is one
            // JSON blob on Android (OAuthManager.loadStoredTokens); JSON-encode +
            // base64 it under "oauthToken", matching iOS 703ff4bc's field name and
            // the existing apiKey / manualOAuthToken base64 encoding. Covers every
            // OAuth provider type via oauthManagerFor (not just the forInstance set).
            run {
                val mgr = oauthManagerFor(instance)
                mgr?.exportStoredTokensJson()?.let { tokenJson ->
                    put("oauthToken", Base64.encodeToString(tokenJson.toByteArray(), Base64.NO_WRAP))
                }
                // Gemini also stores the resolved account email + GCP project as
                // separate OAuth strings (mirrors iOS oauthEmail / oauthGcpProject);
                // carry them so the imported instance can call the API.
                if (instance.providerType == ProviderType.gemini && mgr != null) {
                    mgr.exportOAuthString("email")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthEmail", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                    mgr.exportOAuthString("gcp_project")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthGcpProject", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                }
            }
            instance.customBaseURL?.let { put("customBaseURL", it) }
            if (!instance.appendV1Suffix) put("appendV1Suffix", false)
            if (instance.useResponsesAPI) put("useResponsesAPI", true)
            // [T-provider-custom-user-agent] Additive, optional. Only written
            // when set; old/new readers without the key decode to null →
            // default UA. Field name matches iOS for cross-platform interop.
            instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
        }
        return obj.toString(2)
    }

    /**
     * [T-android-backup-secrets] Collect ONE instance's credentials for the
     * backup `secrets.json`, base64-encoded exactly as [exportInstanceJSON]
     * does (same keychain + OAuth-manager access paths, same field semantics).
     * Returns null when the instance carries no usable credential, so the
     * caller can skip empty entries — matching iOS `BackupSecretsCollector`'s
     * `if !secret.isEmpty` guard.
     */
    fun collectBackupProviderSecret(
        instance: ProviderInstance,
    ): BackupSecrets.ProviderSecret? {
        fun b64(s: String): String =
            Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

        val apiKey = host.loadApiKey(instance.id)?.let(::b64)
        val manualOAuth = com.openminis.app.auth.OAuthManager
            .forInstance(context, instance)?.loadManualBearerToken()
            ?.takeIf { it.isNotEmpty() }?.let(::b64)

        var oauthToken: String? = null
        var oauthEmail: String? = null
        var oauthGcpProject: String? = null
        val mgr = oauthManagerFor(instance)
        if (mgr != null) {
            oauthToken = mgr.exportStoredTokensJson()?.let(::b64)
            if (instance.providerType == ProviderType.gemini) {
                oauthEmail = mgr.exportOAuthString("email")
                    ?.takeIf { it.isNotEmpty() }?.let(::b64)
                oauthGcpProject = mgr.exportOAuthString("gcp_project")
                    ?.takeIf { it.isNotEmpty() }?.let(::b64)
            }
        }

        val secret = BackupSecrets.ProviderSecret(
            instanceId = instance.id,
            label = instance.label,
            providerType = instance.providerType.name,
            apiKey = apiKey,
            manualOAuthToken = manualOAuth,
            oauthToken = oauthToken,
            oauthEmail = oauthEmail,
            oauthGcpProject = oauthGcpProject,
        )
        return if (secret.isEmpty) null else secret
    }

    /**
     * [T-android-backup-secrets] Restore one instance's credentials from a
     * backup `secrets.json` entry. Returns true if any credential was WRITTEN,
     * false if the instance already had a key (kept) or the secret was empty —
     * this true/false is what the importer accumulates into
     * `providersRestored` vs `providersSkippedExisting` (the counts that drive
     * the restore-complete credentials message, iOS parity).
     *
     * Keep-existing policy mirrors iOS: an API key already on this device is
     * NOT overwritten, so restoring a backup onto its origin device reports
     * "kept" rather than "restored".
     */
    fun restoreBackupProviderSecret(secret: BackupSecrets.ProviderSecret): Boolean {
        fun deb64(s: String?): String? = s?.let {
            runCatching { String(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull()
        }
        val instance = host.instance(secret.instanceId) ?: return false
        var wrote = false

        deb64(secret.apiKey)?.let { key ->
            if (host.loadApiKey(instance.id) == null) {
                host.saveApiKey(instance.id, key)
                wrote = true
            }
        }
        deb64(secret.manualOAuthToken)?.let { tok ->
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            if (mgr != null && mgr.loadManualBearerToken().isNullOrEmpty()) {
                mgr.saveManualBearerToken(tok)
                wrote = true
            }
        }
        val mgr = oauthManagerFor(instance)
        if (mgr != null) {
            deb64(secret.oauthToken)?.let { json ->
                if (mgr.exportStoredTokensJson().isNullOrEmpty()) {
                    mgr.importStoredTokensJson(json)
                    wrote = true
                }
            }
            if (instance.providerType == ProviderType.gemini) {
                deb64(secret.oauthEmail)?.let {
                    if (mgr.exportOAuthString("email").isNullOrEmpty()) {
                        mgr.importOAuthString("email", it); wrote = true
                    }
                }
                deb64(secret.oauthGcpProject)?.let {
                    if (mgr.exportOAuthString("gcp_project").isNullOrEmpty()) {
                        mgr.importOAuthString("gcp_project", it); wrote = true
                    }
                }
            }
        }
        return wrote
    }

    /**
     * [T-android-backup-restore-order] Non-destructive, ORDER-PRESERVING union
     * merge of a restored [remote] ProviderConfig into the live config. This is
     * the Android port of iOS `mergeProviderConfigFallback`
     * (BackupImporter+Categories.swift), including the fix `T-backup-restore-order`
     * (b33eb1ff6): the package decides POSITION for the items it carries, so a
     * restore reproduces the backed-up arrangement instead of re-sorting by
     * createdAt.
     *
     * Rules (identical to iOS):
     *  - instances / modelGroups: rebuilt in PACKAGE order. For each remote id,
     *    if the id already exists locally keep the LOCAL element (content wins),
     *    else take the remote element. Local-only ids are appended afterwards in
     *    their original relative order.
     *  - modelEntries: additive by id (append remote ids not present locally);
     *    order not significant (entries are looked up by id, ordered per group).
     *  - agentLoop bindings: set-union.
     *  - per-device pointers (default / voice / vision group ids, session
     *    bindings) are NOT part of ProviderConfig here and are untouched.
     *
     * Returns (beforeInstances, afterInstances) so the caller derives
     * imported = after-before and skipped = package.count - imported — the same
     * counting iOS uses (fix 93cad55ae: union-by-id already-present is SKIPPED,
     * not "updated").
     */
    fun mergeBackupProviderConfig(remote: ProviderConfig): Pair<Int, Int> {
        host.ensureConfigLoaded()
        return synchronized(host.configLock) {
            val local = host.configValue
            val before = local.instances.size

            val orderedInstances = mutableListOf<ProviderInstance>()
            val placedInstances = mutableSetOf<String>()
            for (ri in remote.instances) {
                val existing = local.instances.firstOrNull { it.id == ri.id }
                orderedInstances.add(existing ?: ri)
                placedInstances.add(ri.id)
            }
            for (li in local.instances) {
                if (li.id !in placedInstances) orderedInstances.add(li)
            }

            val mergedEntries = local.modelEntries.toMutableList()
            val entryIds = mergedEntries.map { it.id }.toMutableSet()
            for (entry in remote.modelEntries) {
                if (entry.id !in entryIds) {
                    mergedEntries.add(entry)
                    entryIds.add(entry.id)
                }
            }

            val orderedGroups = mutableListOf<ModelGroup>()
            val placedGroups = mutableSetOf<String>()
            for (rg in remote.modelGroups) {
                val existing = local.modelGroups.firstOrNull { it.id == rg.id }
                orderedGroups.add(existing ?: rg)
                placedGroups.add(rg.id)
            }
            for (lg in local.modelGroups) {
                if (lg.id !in placedGroups) orderedGroups.add(lg)
            }

            val mergedAgentEntries =
                (local.agentLoopModelEntryIds + remote.agentLoopModelEntryIds).distinct()
            val mergedAgentGroups =
                (local.agentLoopGroupIds + remote.agentLoopGroupIds).distinct()

            val merged = local.copy(
                instances = orderedInstances,
                modelEntries = mergedEntries,
                modelGroups = orderedGroups,
                agentLoopModelEntryIds = mergedAgentEntries.toMutableList(),
                agentLoopGroupIds = mergedAgentGroups.toMutableList(),
            )
            host.saveConfig(merged)
            val after = orderedInstances.size
            android.util.Log.i(
                "ProviderRepo",
                "[Restore] provider merge: instances $before→$after " +
                    "entries=${mergedEntries.size} groups=${orderedGroups.size}",
            )
            before to after
        }
    }

    /**
     * [T-android-backup-thinking-rules] Restore custom thinking rules by
     * id-keyed replace. Returns (written, skipped).
     *
     * Divergence from iOS, called out deliberately: iOS does `updated_at` LWW
     * (local equal-or-newer wins). Android's `provider_thinking_rules` table has
     * NO time columns, so there is nothing to compare — a rule already present
     * by id is left as-is (skipped), an absent one is inserted. This is the
     * closest faithful behaviour the local schema allows; the record's carried
     * createdAt/updatedAt are ignored on import.
     */
    fun restoreBackupThinkingRules(
        rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>,
    ): Pair<Int, Int> = runBlocking {
        if (rules.isEmpty()) return@runBlocking 0 to 0
        val existing = host.providerDao.loadAllThinkingRules().map { it.id }.toSet()
        var written = 0
        var skipped = 0
        for (r in rules) {
            if (r.id in existing) { skipped++; continue }
            host.providerDao.upsertThinkingRule(
                com.openminis.app.data.db.ProviderThinkingRuleEntity(
                    id = r.id,
                    providerInstanceId = r.instanceId,
                    label = r.label,
                    scopeKind = r.scopeKind,
                    scopePattern = r.scopePattern,
                    wireFormatJson = r.wireFormatJson.takeIf { it.isNotBlank() && it != "{}" },
                    reasoningEchoJson = null,
                    sortOrder = r.sortOrder,
                )
            )
            written++
        }
        host.loadAllThinkingRulesIntoCache()
        written to skipped
    }

    /**
     * Import a provider from exported JSON. Returns the new instance label on success.
     * - Auto-renames on label conflict
     * - Decodes base64-encoded API key (falls back to plain text)
     */
    fun importInstanceJSON(jsonStr: String): String? {
        host.ensureConfigLoaded()
        val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
        val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
        val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
        val label = dict.optString("label", "").ifEmpty { return null }

        val credentialType = try {
            ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
        } catch (_: Exception) { ProviderCredential.apiKey }

        // Resolve label conflict
        val existingLabels = host.configValue.instances.map { it.label }.toSet()
        var resolvedLabel = label
        if (resolvedLabel in existingLabels) {
            var suffix = 2
            while ("$label ($suffix)" in existingLabels) suffix++
            resolvedLabel = "$label ($suffix)"
        }

        val customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null }
        val appendV1 = dict.optBoolean("appendV1Suffix", true)
        val useResponsesAPI = dict.optBoolean("useResponsesAPI", false)
        // [T-provider-custom-user-agent] Additive: old exports lack the key →
        // empty → null → default UA. Field name matches iOS.
        val customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null }

        val instance = ProviderInstance(
            id = java.util.UUID.randomUUID().toString(),
            label = resolvedLabel,
            providerType = providerType,
            credentialType = credentialType,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1,
            useResponsesAPI = useResponsesAPI,
            customUserAgent = customUserAgent,
        )
        host.addInstance(instance)

        // Decode API key (base64 or plain text)
        val keyValue = dict.optString("apiKey", "").ifEmpty { null }
        if (keyValue != null) {
            val apiKey = try {
                String(Base64.decode(keyValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                keyValue // plain text fallback
            }
            host.saveApiKey(instance.id, apiKey)
        }

        // Decode manual OAuth bearer token (mirrors iOS `manualOAuthToken`).
        // base64-encoded UTF-8 string, with plain-text fallback for older exports.
        val manualTokenValue = dict.optString("manualOAuthToken", "").ifEmpty { null }
        if (manualTokenValue != null) {
            val manualToken = try {
                String(Base64.decode(manualTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                manualTokenValue
            }
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            mgr?.saveManualBearerToken(manualToken)
        }

        // [T-android-provider-export-oauth-token] (XIN 38955) Restore the
        // structured OAuth-login credential so the imported instance is
        // authenticated. Decode base64 → JSON → write back via the OAuth
        // manager. Mirrors iOS 703ff4bc; purely additive alongside the
        // apiKey / manualOAuthToken restore above.
        val oauthTokenValue = dict.optString("oauthToken", "").ifEmpty { null }
        if (oauthTokenValue != null) {
            val tokenJson = try {
                String(Base64.decode(oauthTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                oauthTokenValue // plain-text fallback for hand-edited exports
            }
            oauthManagerFor(instance)?.importStoredTokensJson(tokenJson)
        }
        // Gemini account email + GCP project (base64-encoded OAuth strings).
        if (instance.providerType == ProviderType.gemini) {
            val mgr = oauthManagerFor(instance)
            dict.optString("oauthEmail", "").ifEmpty { null }?.let { b64 ->
                val email = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("email", email)
            }
            dict.optString("oauthGcpProject", "").ifEmpty { null }?.let { b64 ->
                val project = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("gcp_project", project)
            }
        }

        // Import models (replace built-in defaults)
        val models = dict.optJSONArray("models")
        if (models != null && models.length() > 0) {
            val entries = mutableListOf<ModelEntry>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val modelId = m.optString("modelId", "")
                if (modelId.isEmpty()) continue
                val displayName = m.optString("displayName", modelId)
                val isCustom = m.optBoolean("isCustom", false)
                val isHidden = m.optBoolean("isHidden", false)
                val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
                val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
                val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
                val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
                // [T-provider-export-model-overrides] Restore baseModel
                // modalities. Android-native list fields win when present;
                // otherwise fall back to iOS's `modalityOverride` bitfield so
                // a provider exported on iOS retains its capability info.
                val (baseIn, baseOut) = ProviderOverridesWire.readModalitiesWithBitfieldFallback(m)
                val model = LLMModel(
                    id = modelId,
                    displayName = displayName,
                    provider = providerType.displayName,
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    supportsReasoning = supportsReasoning,
                    interleavedReasoningField = interleavedReasoningField,
                    inputModalities = baseIn,
                    outputModalities = baseOut,
                )
                val overridesObj = m.optJSONObject("overrides")
                // [T-provider-export-model-overrides] 整个 overrides 层交给
                // ProviderOverridesWire.decode：逐键 has() 判存在，缺键 → null（=
                // 沿用 baseModel / 默认值），校验口径与上游 1.14 一致 —— 其中就
                // 包含 1.14 新增的 maxThinkingLevel / topP / customHeaders /
                // extraBodyParams；旧导出没有这些键也不会报错。
                val overrides = if (overridesObj != null) {
                    ProviderOverridesWire.decode(overridesObj)
                } else {
                    ModelOverrides()
                }
                entries.add(ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = model,
                    overrides = overrides,
                    isCustom = isCustom,
                    isHidden = isHidden,
                ))
            }
            // Import replaces built-in entries directly (not via replaceEntries which takes LLMModel list).
            // [T-android-provider-mutator-lock] Lock + working copy, like every
            // other mutator. This function is the actual provider.import /
            // Share-import path, and it read `_config.value` back — the object
            // addInstance had just PUBLISHED — and mutated its live list. That
            // is the exact race behind both device CMEs; the earlier sweep
            // missed it because the audit went by function name and this one
            // isn't called add*/update*/remove*.
            synchronized(host.configLock) {
                val cfg = host.workingCopy()
                cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
                cfg.modelEntries.addAll(entries)
                host.saveConfig(cfg)
            }
        }

        return resolvedLabel
    }
}
