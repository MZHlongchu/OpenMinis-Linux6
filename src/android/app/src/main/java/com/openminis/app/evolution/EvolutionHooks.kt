package com.openminis.app.evolution

/**
 * Process-wide hooks so ChatViewModel / SessionActivityTracker do not take
 * an EvolutionEngine constructor dependency.
 */
object EvolutionHooks {
    @Volatile
    var engine: EvolutionEngine? = null

    fun onSessionFinished(sessionId: String, isError: Boolean) {
        engine?.onSessionFinished(sessionId, isError)
    }

    fun onToolFailure(sessionId: String, toolName: String, argsJson: String, error: String) {
        engine?.recordToolFailure(sessionId, toolName, argsJson, error)
    }

    fun maybeHarvestIdle() {
        engine?.maybeHarvestIdle()
    }
}
