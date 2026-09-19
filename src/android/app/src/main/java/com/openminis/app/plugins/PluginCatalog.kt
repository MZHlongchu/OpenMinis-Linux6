package com.openminis.app.plugins

/**
 * One-tap MCP server presets for the plugin market.
 *
 * Adapted from XINCODE-Public PluginStore MCP catalog entries
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 * GitHub-token connectors are intentionally not ported.
 */
object PluginCatalog {

    data class McpPreset(
        val id: String,
        val name: String,
        val description: String,
        val url: String,
    )

    val MCP_PRESETS: List<McpPreset> = listOf(
        McpPreset(
            id = "microsoft_learn_mcp",
            name = "Microsoft Learn",
            description = "Microsoft documentation search over MCP.",
            url = "https://learn.microsoft.com/api/mcp",
        ),
        McpPreset(
            id = "context7_mcp",
            name = "Context7",
            description = "Up-to-date library docs for coding agents.",
            url = "https://mcp.context7.com/mcp",
        ),
        McpPreset(
            id = "deepwiki_mcp",
            name = "DeepWiki",
            description = "Ask questions about public GitHub repositories.",
            url = "https://mcp.deepwiki.com/mcp",
        ),
    )
}
