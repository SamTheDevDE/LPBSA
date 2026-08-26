package de.samthedev.lpbsa.access

import de.samthedev.lpbsa.config.canonicalServerName

sealed interface FallbackPlan {
    data class Redirect(val server: String) : FallbackPlan
    data class Disconnect(val reason: String) : FallbackPlan
}

class FallbackPlanner {
    fun plan(
        deniedServer: String,
        fallbackServer: String,
        registeredServers: Set<String>,
        fallbackDecision: AccessDecision?,
    ): FallbackPlan {
        val denied = canonicalServerName(deniedServer)
        val fallback = canonicalServerName(fallbackServer)
        val registered = registeredServers.mapTo(mutableSetOf(), ::canonicalServerName)
        if (fallback.isBlank()) return FallbackPlan.Disconnect("fallback is blank")
        if (fallback == denied) {
            return FallbackPlan.Disconnect("fallback equals denied server")
        }
        if (fallback !in registered) return FallbackPlan.Disconnect("fallback is not registered")
        if (fallbackDecision?.allowed != true) return FallbackPlan.Disconnect("fallback is not accessible")
        return FallbackPlan.Redirect(fallback)
    }
}
