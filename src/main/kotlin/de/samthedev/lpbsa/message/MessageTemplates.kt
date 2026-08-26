package de.samthedev.lpbsa.message

data class MessageTemplates(
    val configVersion: Int,
    val prefix: String,
    val values: Map<String, String>,
) {
    fun template(key: String): String? = values[key]
}

object MessageKeys {
    val required = setOf(
        "server-access-denied",
        "initial-access-denied",
        "redirecting",
        "fallback-unavailable",
        "reload-success",
        "reload-failed",
        "no-permission",
        "unknown-server",
        "player-not-found",
        "player-only",
        "check-allowed",
        "check-denied",
        "check-detail",
        "test-allowed",
        "test-denied",
        "overview",
        "help-header",
        "help-entry",
        "status-header",
        "status-entry",
        "servers-header",
        "server-entry",
        "version-info",
        "invalid-usage",
    )
}
