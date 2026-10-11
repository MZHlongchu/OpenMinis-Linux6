package com.openminis.app.provider.openrouter

import com.openminis.app.provider.ModelListFetchIsolation
import com.openminis.app.provider.ModelListFetchRetry
import com.openminis.app.provider.ProviderKeyGate
import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.VoiceRole
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.ProviderModelsCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.openminis.app.network.SharedHttpClients
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenRouterModelsApi {
    // [T-merge-1.14] 客户端保留我们的审计修复（SharedHttpClients：统一超时/连接池/
    // 代理与 UA，且不再每次拉目录都新建 OkHttpClient）；缓存命名空间采纳上游的
    // "-v2"——语音/转写目录合并后，旧条目没有 voiceRole，7 天内会一直命中坏数据。
    private val client = SharedHttpClients.default
    private val cache = ProviderModelsCache("openrouter-v2")

    private const val MODELS_URL = "https://openrouter.ai/api/v1/models"

    /**
     * Fetch OpenRouter's model catalog. OpenRouter returns rich metadata
     * alongside each model (context window, max output, reasoning support,
     * input/output modalities) — we parse it here instead of waiting for
     * `ModelsDevApi.enrichModels` to match, matching iOS `OpenRouterModelsAPI`.
     *
     * @param context When provided, results are cached for 7 days at
     *   `cacheDir/models-cache/openrouter-v2/<sha256(apiKey)>.json`. Pass null to
     *   bypass the cache entirely (matches existing callers).
     * @param forceRefresh Skip the cache read but still write-through on success.
     */
    suspend fun fetchModels(
        apiKey: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
        cacheScope: String = "",
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = ModelListFetchIsolation.cacheKey(apiKey, cacheScope)
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        // [T-openrouter-voice-catalog] OpenMinis#280. The default list holds
        // only chat models; speech and transcription models are listed solely
        // by their output_modalities filters. Fetch all three at once and tag
        // each model with the list it came from.
        val (defaultResult, speech, transcription) = coroutineScope {
            val d = async { fetchList(MODELS_URL, apiKey, forceRefresh) }
            val tts = async { fetchList("$MODELS_URL?output_modalities=speech", apiKey, forceRefresh) }
            val stt = async { fetchList("$MODELS_URL?output_modalities=transcription", apiKey, forceRefresh) }
            Triple(d.await(), tts.await(), stt.await())
        }

        val defaultModels = when (defaultResult) {
            is ListResult.Ok -> defaultResult.models
            is ListResult.Failed -> {
                // [T-models-fetch-transient-auth] 走到这里时已经过有界重试，只有最后
                // 一次尝试的 401/403 才会让缓存失效——一次瞬时抖动不该抹掉可用目录。
                if (context != null && (defaultResult.code == 401 || defaultResult.code == 403)) {
                    cache.invalidate(context, cacheKey)
                }
                return@withContext emptyList()
            }
        }
        // A failed filter endpoint only costs its voice models; the chat
        // catalog still loads.
        val speechModels = (speech as? ListResult.Ok)?.models.orEmpty()
        val transcriptionModels = (transcription as? ListResult.Ok)?.models.orEmpty()
        if (speech is ListResult.Failed || transcription is ListResult.Failed) {
            android.util.Log.w(
                "OpenRouterModelsApi",
                "voice catalog partial: speech=${speech.describe()} transcription=${transcription.describe()}",
            )
        }

        val merged = mergeVoiceCatalogs(
            ModelsDevApi.enrichModels(defaultModels),
            speechModels,
            transcriptionModels,
        )
        if (context != null) cache.save(context, cacheKey, merged)
        merged
    }

    private sealed class ListResult {
        class Ok(val models: List<LLMModel>) : ListResult()
        class Failed(val code: Int) : ListResult()

        fun describe(): String = when (this) {
            is Ok -> "${models.size}"
            is Failed -> "failed(HTTP $code)"
        }
    }

    /**
     * One catalog GET, hardened by our audit fixes and shared by all three URLs
     * the voice catalogs need:
     *
     *  - [ModelListFetchIsolation]: cache defeat by header only — never a
     *    query-string cache buster, since some gateways 404 any query string.
     *  - [ModelListFetchRetry]: one sample of an idempotent GET is not proof
     *    about a credential, so a single transient non-2xx is not final.
     *
     * A failure is a value, never a propagated exception: a filter list is
     * allowed to fail on its own while the chat catalog still loads.
     */
    private suspend fun fetchList(
        url: String,
        apiKey: String,
        forceRefresh: Boolean = false,
    ): ListResult = try {
        val request = ModelListFetchIsolation.run {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://github.com/OpenMinis/OpenMinis")
                .header("X-Title", "Minis App")
                // [T-android-default-ua] brand outbound /api/v1/models request.
                .applyUserAgentOverride(null)
                .noStoreIf(forceRefresh)
                .build()
        }
        // [T-models-fetch-transient-auth] Bounded retry: a single non-2xx used
        // to be final, which invalidated the cache and returned an empty list.
        ModelListFetchRetry.execute(
            client,
            request,
            "OpenRouterModels",
            credentialKey = ProviderKeyGate.credentialKey("https://openrouter.ai/api/v1", apiKey),
        ).use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                ListResult.Failed(response.code)
            } else {
                val data = JSONObject(body).optJSONArray("data")
                if (data == null) ListResult.Failed(response.code) else ListResult.Ok(parseModels(data))
            }
        }
    } catch (e: CancellationException) {
        // 协程已取消就按取消传播（CancellationException），别伪装成一次目录拉取失败。
        throw e
    } catch (_: Exception) {
        ListResult.Failed(-1)
    }

    /**
     * [T-openrouter-voice-catalog] Tag every model with the catalog it came
     * from and give voice models their exact dedicated shape. The filtered
     * lists declare `speech` / `transcription` rather than `audio`, so without
     * the shape they would show no audio at all.
     *
     *   default list        → voiceRole "none", modalities as declared
     *   speech filter       → voiceRole "tts",  text in → audio out
     *   transcription filter → voiceRole "stt",  audio in → text out
     *
     * The filtered lists win on an id collision (none today), since the
     * filter IS the authoritative "this is a voice model" signal. Order: chat
     * catalog first, then TTS, then ASR, each in the order OpenRouter returned.
     */
    internal fun mergeVoiceCatalogs(
        defaultModels: List<LLMModel>,
        speech: List<LLMModel>,
        transcription: List<LLMModel>,
    ): List<LLMModel> {
        val byId = LinkedHashMap<String, LLMModel>()
        for (m in defaultModels) byId[m.id] = m.copy(voiceRole = VoiceRole.NONE)
        for (m in speech) {
            byId[m.id] = m.copy(
                voiceRole = VoiceRole.TTS,
                inputModalities = listOf("text"),
                outputModalities = listOf("audio"),
            )
        }
        for (m in transcription) {
            byId[m.id] = m.copy(
                voiceRole = VoiceRole.STT,
                inputModalities = listOf("audio"),
                outputModalities = listOf("text"),
            )
        }
        return byId.values.toList()
    }

    private fun parseModels(data: JSONArray): List<LLMModel> {
        val models = mutableListOf<LLMModel>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            val name = obj.optString("name", "").ifBlank { LLMModel.modelDisplayName(id) }

            // OpenRouter exposes `architecture.input_modalities` / `output_modalities`
            // as arrays of short strings (e.g. "text", "image", "audio"). Fall back
            // to `[text, text]` when absent — most text-only models omit these.
            val arch = obj.optJSONObject("architecture")
            // OpenRouter returns modalities as `image_input` / `text_output` with suffixes;
            // the rest of the codebase (models.dev, capability fragments,
            // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
            // boundary so persisted overrides round-trip correctly through the toggles.
            val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
            val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

            val contextWindow = obj.optInt("context_length").takeIf { it > 0 }
            val maxOutputTokens = obj.optJSONObject("top_provider")
                ?.optInt("max_completion_tokens")?.takeIf { it > 0 }

            val supportedParams = obj.optJSONArray("supported_parameters")?.toStringList() ?: emptyList()
            val supportsReasoning = "reasoning" in supportedParams

            models.add(
                LLMModel(
                    id = id,
                    displayName = name,
                    provider = "OpenRouter",
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    supportsReasoning = if (supportsReasoning) true else null,
                    inputModalities = inputModalities,
                    outputModalities = outputModalities,
                )
            )
        }
        return models
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
