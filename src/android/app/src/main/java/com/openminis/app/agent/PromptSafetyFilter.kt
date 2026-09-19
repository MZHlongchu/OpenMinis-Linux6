package com.openminis.app.agent

/**
 * Shared save-time / render-time filter for user-authored prompt templates
 * and workspace rules. Rejects classic prompt-injection plus the jailbreak
 * lock-in phrasing this app will not host.
 */
object PromptSafetyFilter {

    private val EXTRA_PATTERNS: List<Regex> = listOf(
        Regex("never.{0,24}refuse", RegexOption.IGNORE_CASE),
        Regex("永不拒绝"),
        Regex("ignore.{0,24}(safety|policy|guidelines)", RegexOption.IGNORE_CASE),
        Regex("unrestricted sandbox", RegexOption.IGNORE_CASE),
        Regex("身份锁定"),
        Regex("破甲"),
        Regex("jailbreak", RegexOption.IGNORE_CASE),
        Regex("you have no (ethical |moral )?restrictions", RegexOption.IGNORE_CASE),
        Regex("start every reply with the deliverable", RegexOption.IGNORE_CASE),
        Regex("do not treat them as untrusted", RegexOption.IGNORE_CASE),
        Regex("refusal.{0,20}(ban|blacklist|hardening)", RegexOption.IGNORE_CASE),
        Regex("boundary.?word.{0,12}normali[sz]ation", RegexOption.IGNORE_CASE),
    )

    fun containsUnsafe(s: String): Boolean =
        SystemPromptBuilder.containsInjectionPattern(s) || EXTRA_PATTERNS.any { it.containsMatchIn(s) }

    fun scrub(s: String): String =
        s.lineSequence()
            .filter { line -> !containsUnsafe(line) }
            .joinToString("\n")
            .trim()
}
