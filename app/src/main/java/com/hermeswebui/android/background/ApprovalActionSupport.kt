package com.hermeswebui.android.background

internal object ApprovalActionSupport {
    private val allowChoicePriority = listOf("once", "session", "always")
    private val denyChoiceAliases = listOf("deny", "reject")

    fun normalizeChoice(choice: String?): String? {
        return choice?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }

    fun preferredAllowChoice(choices: List<String>): String? {
        val normalizedChoices = choices.mapNotNull(::normalizeChoice)
        return allowChoicePriority.firstOrNull { it in normalizedChoices }
    }

    fun denyChoice(choices: List<String>): String? {
        val normalizedChoices = choices.mapNotNull(::normalizeChoice)
        return denyChoiceAliases.firstOrNull { it in normalizedChoices }
    }

    fun labelForChoice(choice: String): String {
        return when (normalizeChoice(choice)) {
            "once" -> "Allow once"
            "session" -> "Allow session"
            "always" -> "Always allow"
            "deny", "reject" -> "Deny"
            else -> "Respond"
        }
    }
}