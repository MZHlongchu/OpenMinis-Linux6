package com.openminis.app.ui.chat

/**
 * [T-scheduled-preemptive-insert] Which queued prompts go out together, and
 * when.
 *
 * Two delivery points exist. The TOOL BOUNDARY — a turn's tool calls have all
 * finished and their results are in history, the next model request has not
 * been made — is where a prompt can be slipped into a running loop without
 * cutting any tool short. The DRAIN runs once the loop has converged (and
 * after a Stop).
 *
 * The rules, and why:
 *  - A scheduled fire always travels ALONE. It carries its own envelope and
 *    possibly its own prefilled tool call; merged with other prompts it
 *    became one message holding several unrelated triggers, and a prefill
 *    had no single turn to belong to.
 *  - At the tool boundary only a user follow-up or a scheduled fire may go
 *    in. Other programmatic prompts (agent callbacks, RPC/CLI sends) keep
 *    waiting for the drain, as [QueuedPromptOrigin] describes.
 *  - One scheduled fire per boundary. Several pending fires are delivered one
 *    boundary (or drain round) at a time, oldest first, so the original task
 *    still gets a model turn between them.
 *  - Non-scheduled prompts keep their old behaviour: merged into one message.
 */
internal object QueuedPromptBatching {

    /**
     * [T-queue-shelved] 搁置态的消息不参与任何自动投递。
     *
     * 本移植的 [QueuedPrompt.shelved] 是上游没有的：打断/异常后滞留在队列里的
     * 消息进入搁置态，用户点「重新排队」才恢复。这个判断原先散在每个调用点的
     * `_promptQueue.value.any { !it.shelved }` 守卫里，而上游 1.14 把"该投哪一批"
     * 收进了本文件——两边合起来就必须在这里过滤，否则 `nextInsertBatch` 会把一条
     * 搁置的 user 消息当成"用户跟进"投出去，正好是搁置态要防的事。放在这里也
     * 让边界与 drain 共用同一条规则（调用点照上游写 `nextInsertBatch(_promptQueue.value)`）。
     */
    private fun deliverable(queue: List<QueuedPrompt>): List<QueuedPrompt> = queue.filter { !it.shelved }

    /** What to slip in at a tool boundary; empty = nothing may go in now. */
    fun nextInsertBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val live = deliverable(queue)
        val first = live.firstOrNull { it.isScheduledFire || it.origin == QueuedPromptOrigin.USER }
            ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else live.filterNot { it.isScheduledFire }
    }

    /** What the drain sends as its next turn; empty = the queue is empty. */
    fun nextDrainBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val live = deliverable(queue)
        val first = live.firstOrNull() ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else live.filterNot { it.isScheduledFire }
    }

    /**
     * Ids of queued fires a new fire of [taskId] replaces. Two undelivered
     * fires of the same task would run the same check twice in a row; the
     * newer one has the fresher prefilled output, so it wins. Fires of
     * different tasks never replace each other.
     */
    fun supersededBy(queue: List<QueuedPrompt>, taskId: String): List<String> =
        queue.filter { it.scheduledTaskId == taskId }.map { it.id }
}
