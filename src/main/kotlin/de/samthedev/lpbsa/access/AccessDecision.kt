package de.samthedev.lpbsa.access

import de.samthedev.lpbsa.config.RequirementMode

sealed interface AccessDecision {
    val allowed: Boolean

    data object OpenServer : AccessDecision {
        override val allowed = true
    }

    data class AllowedByGlobalBypass(val permission: String) : AccessDecision {
        override val allowed = true
    }

    data class AllowedByServerBypass(val permission: String) : AccessDecision {
        override val allowed = true
    }

    data class AllowedByRequirement(
        val matched: List<String>,
        val mode: RequirementMode,
    ) : AccessDecision {
        override val allowed = true
    }

    data class Denied(val reason: DenialReason) : AccessDecision {
        override val allowed = false
    }

    data class Failure(val cause: Throwable, val failOpen: Boolean) : AccessDecision {
        override val allowed = failOpen
    }
}

sealed interface DenialReason {
    data object EmptyRequirements : DenialReason

    data class RequirementsNotMet(
        val mode: RequirementMode,
        val matched: List<String>,
        val missing: List<String>,
    ) : DenialReason
}

fun AccessDecision.summary(): String = when (this) {
    AccessDecision.OpenServer -> "server is open"
    is AccessDecision.AllowedByGlobalBypass -> "global bypass $permission"
    is AccessDecision.AllowedByServerBypass -> "server bypass $permission"
    is AccessDecision.AllowedByRequirement -> "matched ${matched.joinToString()}"
    is AccessDecision.Denied -> when (val denial = reason) {
        DenialReason.EmptyRequirements -> "restricted rule has no valid requirements"
        is DenialReason.RequirementsNotMet -> "missing ${denial.missing.joinToString()}"
    }
    is AccessDecision.Failure -> "authorization failure (${if (failOpen) "fail-open" else "fail-closed"})"
}
