package de.samthedev.lpbsa.access

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
        if (fallbackServer.isBlank()) return FallbackPlan.Disconnect("fallback is blank")
        if (fallbackServer.equals(deniedServer, ignoreCase = true)) {
            return FallbackPlan.Disconnect("fallback equals denied server")
        }
        if (fallbackServer !in registeredServers) return FallbackPlan.Disconnect("fallback is not registered")
        if (fallbackDecision?.allowed != true) return FallbackPlan.Disconnect("fallback is not accessible")
        return FallbackPlan.Redirect(fallbackServer)
    }
}
