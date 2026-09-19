package com.openminis.app.security

/**
 * Argv tokenizer and expansion-syntax detector.
 *
 * Adapted from XINCODE-Public ExecPolicy.kt (GPL-3.0-or-later)
 * which itself ports Codex (Apache-2.0) quoting rules.
 * https://github.com/kusesad-1122/XINCODE-Public
 */
fun tokenizeCommand(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = '\u0000'
    var i = 0
    var hasToken = false
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '\\' && i + 1 < raw.length -> {
                cur.append(raw[i + 1]); i += 2; hasToken = true; continue
            }
            quote != '\u0000' -> {
                if (c == quote) quote = '\u0000' else { cur.append(c); hasToken = true }
            }
            c == '\'' || c == '"' -> quote = c
            c.isWhitespace() -> {
                if (hasToken || cur.isNotEmpty()) {
                    out.add(cur.toString()); cur.clear(); hasToken = false
                }
            }
            else -> { cur.append(c); hasToken = true }
        }
        i++
    }
    if (hasToken || cur.isNotEmpty()) out.add(cur.toString())
    return out
}

val APP_DATA_ROOTS = listOf(
    "/data/data/com.openminis.linux",
    "/data/user/0/com.openminis.linux",
)

fun containsShellExpansionSyntax(raw: String): Boolean {
    if (raw.isEmpty()) return false
    if (raw.contains('$')) return true
    if (raw.contains('`')) return true
    if (raw.contains(';')) return true
    if (raw.contains('|')) return true
    if (raw.contains('&')) return true
    if (raw.contains('>')) return true
    if (raw.contains('\n') || raw.contains('\r')) return true
    if (Regex("\\{[^}\\s]*,[^}\\s]*\\}").containsMatchIn(raw)) return true
    return false
}
