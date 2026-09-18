package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasImageInput

/**
 * When a bound model group is asked to handle a turn that needs a modality
 * the currently selected member lacks, prefer a credentialed member that
 * actually supports it. Falls back to the original pool when nobody in the
 * group has the capability (Vision Group / other fallbacks still apply).
 */
enum class ModelCapability {
    IMAGE_INPUT,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    ;

    fun isSupportedBy(model: LLMModel): Boolean = when (this) {
        IMAGE_INPUT -> model.hasImageInput
        AUDIO_INPUT -> model.hasAudioInput
        AUDIO_OUTPUT -> model.hasAudioOutput
    }
}

object CapabilityRouter {

    fun neededForImages(hasImage: Boolean): Set<ModelCapability> =
        if (hasImage) setOf(ModelCapability.IMAGE_INPUT) else emptySet()

    fun pickMembers(
        available: List<ModelEntry>,
        needed: Set<ModelCapability>,
    ): List<ModelEntry> {
        if (needed.isEmpty() || available.isEmpty()) return available
        val hit = available.filter { entry ->
            needed.all { it.isSupportedBy(entry.model) }
        }
        return hit.ifEmpty { available }
    }
}
