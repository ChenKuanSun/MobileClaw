package ai.affiora.mobileclaw.ui.chat

import ai.affiora.mobileclaw.agent.PermissionManager

data class SlashCommand(
    val name: String,
    val description: String,
    val category: String,
    val action: suspend (args: String, viewModel: ChatViewModel) -> Unit,
)

object SlashCommands {

    val all: List<SlashCommand> = listOf(
        SlashCommand(
            name = "/help",
            description = "Show all available commands",
            category = "Info",
            action = { _, viewModel ->
                val helpText = buildString {
                    appendLine("**Available Commands:**")
                    appendLine()
                    var currentCategory = ""
                    for (cmd in all.sortedBy { it.category }) {
                        if (cmd.category != currentCategory) {
                            currentCategory = cmd.category
                            appendLine("**$currentCategory**")
                        }
                        appendLine("  `${cmd.name}` — ${cmd.description}")
                    }
                }
                viewModel.insertSystemMessage(helpText)
            },
        ),
        SlashCommand(
            name = "/clear",
            description = "Clear current conversation messages",
            category = "Session",
            action = { _, viewModel ->
                viewModel.clearCurrentConversation()
            },
        ),
        SlashCommand(
            name = "/new",
            description = "Start a new conversation",
            category = "Session",
            action = { _, viewModel ->
                viewModel.startNewConversation()
            },
        ),
        SlashCommand(
            name = "/stop",
            description = "Cancel current agent run",
            category = "Session",
            action = { _, viewModel ->
                viewModel.cancelAgentRun()
            },
        ),
        SlashCommand(
            name = "/model",
            description = "Switch model (e.g. /model claude-sonnet-4-20250514)",
            category = "Model",
            action = { args, viewModel ->
                if (args.isBlank()) {
                    viewModel.insertSystemMessage("Usage: `/model <model-name>`\nCurrent model: ${viewModel.getCurrentModel()}")
                } else {
                    viewModel.switchModel(args.trim())
                }
            },
        ),
        SlashCommand(
            name = "/compact",
            description = "Summarize conversation to reduce context",
            category = "Session",
            action = { _, viewModel ->
                viewModel.compactConversation()
            },
        ),
        SlashCommand(
            name = "/status",
            description = "Show current model, provider, token count",
            category = "Info",
            action = { _, viewModel ->
                val status = viewModel.getStatus()
                viewModel.insertSystemMessage(status)
            },
        ),
        SlashCommand(
            name = "/export",
            description = "Share conversation as text",
            category = "Info",
            action = { _, viewModel ->
                viewModel.exportConversation()
            },
        ),
        SlashCommand(
            name = "/think",
            description = "Set thinking level (off|low|medium|high)",
            category = "Model",
            action = { args, viewModel ->
                val level = args.trim().lowercase()
                if (level !in listOf("off", "low", "medium", "high")) {
                    viewModel.insertSystemMessage("Usage: `/think <off|low|medium|high>`")
                } else {
                    viewModel.setThinkingLevel(level)
                    viewModel.insertSystemMessage("Thinking level set to **$level**")
                }
            },
        ),
        SlashCommand(
            name = "/bypass",
            description = "Toggle bypass mode (auto-approve all tools)",
            category = "Permissions",
            action = { _, viewModel ->
                val pm = viewModel.getPermissionManager()
                val current = pm.mode.value
                if (current == PermissionManager.PermissionMode.BYPASS_ALL) {
                    pm.setMode(PermissionManager.PermissionMode.DEFAULT)
                    viewModel.insertSystemMessage("Bypass mode **OFF**. Switched to Default mode.")
                } else {
                    pm.setMode(PermissionManager.PermissionMode.BYPASS_ALL)
                    viewModel.insertSystemMessage("Bypass mode **ON**. All tool actions will be auto-approved.")
                }
            },
        ),
        SlashCommand(
            name = "/approve",
            description = "Add tool to permanent allowlist (e.g. /approve sms)",
            category = "Permissions",
            action = { args, viewModel ->
                val toolName = args.trim()
                if (toolName.isBlank()) {
                    val allowed = viewModel.getPermissionManager().allowedTools.value
                    val list = if (allowed.isEmpty()) "none" else allowed.sorted().joinToString(", ")
                    viewModel.insertSystemMessage("**Allowed tools:** $list\n\nUsage: `/approve <tool_name>`")
                } else {
                    val available = viewModel.getToolNames()
                    if (toolName !in available) {
                        viewModel.insertSystemMessage("Unknown tool: `$toolName`. Available: ${available.joinToString(", ")}")
                    } else {
                        viewModel.getPermissionManager().allowTool(toolName)
                        viewModel.insertSystemMessage("Tool `$toolName` added to permanent allowlist.")
                    }
                }
            },
        ),
        SlashCommand(
            name = "/deny",
            description = "Remove tool from allowlist (e.g. /deny sms)",
            category = "Permissions",
            action = { args, viewModel ->
                val toolName = args.trim()
                if (toolName.isBlank()) {
                    viewModel.insertSystemMessage("Usage: `/deny <tool_name>`")
                } else {
                    viewModel.getPermissionManager().denyTool(toolName)
                    viewModel.insertSystemMessage("Tool `$toolName` removed from allowlist.")
                }
            },
        ),
        SlashCommand(
            name = "/install",
            description = "Install a skill from URL. AI adapts it for Android.",
            category = "Skills",
            action = { args, viewModel ->
                if (args.isBlank()) {
                    viewModel.insertSystemMessage("Usage: /install <url>\nExample: /install https://clawhub.com/skills/weather/SKILL.md")
                    return@SlashCommand
                }
                viewModel.installSkillFromUrl(args.trim())
            },
        ),
        SlashCommand(
            name = "/uninstall",
            description = "Remove an installed skill",
            category = "Skills",
            action = { args, viewModel ->
                if (args.isBlank()) {
                    viewModel.insertSystemMessage("Usage: /uninstall <skill-id>")
                    return@SlashCommand
                }
                viewModel.uninstallSkill(args.trim())
            },
        ),
        SlashCommand(
            name = "/create",
            description = "Create a new skill with AI guidance. Describe what you want.",
            category = "Skills",
            action = { args, viewModel ->
                if (args.isBlank()) {
                    viewModel.sendMessage("I want to create a new skill. Help me design it step by step. Ask me what I want the skill to do, what triggers it, and what tools it should use. Then create it with the skills_author tool.")
                } else {
                    viewModel.sendMessage("I want to create a new skill: $args. Help me refine this idea. Ask clarifying questions about triggers, workflow steps, and which tools to use. Then create the skill with the skills_author tool.")
                }
            },
        ),
        SlashCommand(
            name = "/btw",
            description = "Ask a side question without changing conversation context.",
            category = "Chat",
            action = { args, viewModel ->
                if (args.isBlank()) {
                    viewModel.insertSystemMessage("Usage: /btw <question>\nAsk a quick side question. The answer won't affect the main conversation flow.")
                } else {
                    viewModel.sendMessage("[Side question — answer briefly without changing the main topic]: $args")
                }
            },
        ),
    )

    fun findMatches(query: String): List<SlashCommand> {
        if (query.isBlank()) return all
        val lower = query.lowercase()
        return all.filter { it.name.lowercase().startsWith(lower) }
    }
}
