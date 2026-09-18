package com.openminis.app.notification

/**
 * Preset sandbox commands injected from task-complete notification actions.
 *
 * The broadcast extra carries only an action key — never a free-form command —
 * so a third-party app cannot smuggle a payload even if it somehow targeted
 * the exported=false receiver.
 */
object SandboxNotifyActions {
    const val ACTION = "com.openminis.linux.action.SANDBOX_NOTIFY"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_ACTION = "action"

    const val RETRY = "retry"
    const val BACKUP = "backup"
    const val CLEANUP = "cleanup"

    const val LAST_CMD_GUEST_PATH = "/var/minis/workspace/.minis-last-cmd.sh"

    fun commandFor(action: String): String? = when (action) {
        RETRY -> """
            # minis-notify-action retry
            if [ -f $LAST_CMD_GUEST_PATH ]; then
              sh $LAST_CMD_GUEST_PATH
            else
              echo 'nothing to retry'
            fi
        """.trimIndent()
        BACKUP -> """
            # minis-notify-action backup
            mkdir -p /var/minis/workspace/.minis-backup
            tar -czf "/var/minis/workspace/.minis-backup/workspace-\$(date +%Y%m%d-%H%M%S).tgz" \
              --exclude .minis-backup -C /var/minis/workspace . 2>/dev/null
            echo backup-ok
        """.trimIndent()
        CLEANUP -> """
            # minis-notify-action cleanup
            rm -rf /tmp/* /var/tmp/* 2>/dev/null || true
            echo cleaned
        """.trimIndent()
        else -> null
    }

    fun shouldRecordLastCommand(command: String): Boolean {
        if (command.contains("minis-notify-action")) return false
        if (command.contains(".minis-last-cmd.sh")) return false
        return command.isNotBlank()
    }
}
