package com.openminis.app.tools

/**
 * Coordinator → sub-agent task envelope. Sub-agents cannot see the parent
 * conversation, so every dispatch must be self-contained. The runtime always
 * wraps the coordinator's prompt so Collaboration rules are present even when
 * the model omits the markdown sections.
 */
object SubAgentBrief {

    const val TASK = "## Task"
    const val EXPECTED = "## Expected result"
    const val CONSTRAINTS = "## Constraints"
    const val WORKFLOW = "## Workflow"
    const val COLLABORATION = "## Collaboration"

    /** Hint injected into the coordinator system prompt and run_subagent tool. */
    const val COORDINATOR_SPEC = """Each run_subagent prompt MUST include these markdown sections:
## Task — the slice to complete (paths, files, what "done" means)
## Expected result — acceptance criteria the coordinator will verify
## Constraints — what not to touch, write_paths, kind limits
## Workflow — ordered steps (read → change → verify → report)
## Collaboration — you are a teammate, not the coordinator; do not call run_subagent
Independent slices: emit multiple run_subagent calls in ONE turn. Dependent phases: accept the previous result before dispatching the next. Never ask a sub-agent to spawn further sub-agents."""

    fun wrap(
        rawPrompt: String,
        kind: String,
        role: String? = null,
        writePaths: List<String> = emptyList(),
    ): String {
        val body = rawPrompt.trim()
        val roleLine = role?.trim()?.takeIf { it.isNotEmpty() }?.let { "- Assigned role: $it\n" } ?: ""
        val writeLine = if (writePaths.isNotEmpty()) {
            "- You may only file_write/file_edit under: ${writePaths.joinToString()}\n"
        } else {
            ""
        }
        val collaboration = """$COLLABORATION
You are a sub-agent (kind=$kind), not the session coordinator. You cannot see the parent chat. Complete ONLY this slice and return a report.
- Do not call run_subagent or spawn teammates. Nested dispatch is blocked.
- Do not rewrite unrelated files or expand the scope.
$roleLine$writeLine- If you cannot meet the expected result, say so explicitly and list what failed.""".trimIndent()

        if (isStructured(body)) {
            return if (body.contains(COLLABORATION)) {
                body
            } else {
                "$body\n\n$collaboration"
            }
        }

        return """
$TASK
$body

$EXPECTED
Meet the acceptance criteria in the task. If none were given, deliver a concise report of what changed, files touched, leftover risks, and pass/fail.

$CONSTRAINTS
- Complete ONLY this slice. Do not rewrite unrelated files.
- Do not call run_subagent or spawn teammates.
$roleLine$writeLine
$WORKFLOW
1. Read only the files you need.
2. Make the smallest change that satisfies the task.
3. Verify against the expected result.
4. Return a report: changed files, leftover risks, pass/fail.

$collaboration
""".trimIndent()
    }

    fun isStructured(prompt: String): Boolean {
        val t = prompt
        return t.contains(TASK) && t.contains(EXPECTED) && t.contains(CONSTRAINTS) && t.contains(WORKFLOW)
    }
}
