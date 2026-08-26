package de.samthedev.lpbsa.config

const val SUPPORTED_CONFIG_VERSION = 1

enum class DefaultPolicy { OPEN, RESTRICTED }
enum class FailMode { CLOSED, OPEN }
enum class RequirementMode { ANY, ALL }
enum class TransferAction { STAY, REDIRECT, DISCONNECT }
enum class InitialAction { REDIRECT, DISCONNECT }
enum class NotificationLocation { CHAT, ACTION_BAR, BOTH, NONE }

data class Requirements(
    val mode: RequirementMode,
    val permissions: List<String>,
    val groups: List<String>,
) {
    val entries: List<String>
        get() = permissions + groups.map { "group:$it" }
}

data class AccessProfile(
    val name: String,
    val requirements: Requirements,
)

data class DenialOverrides(
    val transferAction: TransferAction? = null,
    val initialAction: InitialAction? = null,
    val fallbackServer: String? = null,
    val notification: NotificationLocation? = null,
    val messageCooldownMs: Long? = null,
    val message: String? = null,
)

data class DenialSettings(
    val transferAction: TransferAction = TransferAction.STAY,
    val initialAction: InitialAction = InitialAction.REDIRECT,
    val fallbackServer: String = "lobby",
    val notification: NotificationLocation = NotificationLocation.CHAT,
    val messageCooldownMs: Long = 1_000,
) {
    fun merge(overrides: DenialOverrides): ResolvedDenial = ResolvedDenial(
        transferAction = overrides.transferAction ?: transferAction,
        initialAction = overrides.initialAction ?: initialAction,
        fallbackServer = overrides.fallbackServer ?: fallbackServer,
        notification = overrides.notification ?: notification,
        messageCooldownMs = overrides.messageCooldownMs ?: messageCooldownMs,
        message = overrides.message ?: "server-access-denied",
    )
}

data class ResolvedDenial(
    val transferAction: TransferAction,
    val initialAction: InitialAction,
    val fallbackServer: String,
    val notification: NotificationLocation,
    val messageCooldownMs: Long,
    val message: String,
)

data class LoggingSettings(
    val deniedConnections: Boolean = true,
    val allowedConnections: Boolean = false,
)

data class ServerRule(
    val server: String,
    val enabled: Boolean,
    val requirements: Requirements,
    val profile: String? = null,
    val bypassPermission: String = "lpbsa.bypass.$server",
    val denial: DenialOverrides = DenialOverrides(),
)

data class RuntimeConfig(
    val configVersion: Int,
    val debug: Boolean,
    val defaultPolicy: DefaultPolicy,
    val globalBypassPermission: String,
    val failMode: FailMode,
    val strictServerValidation: Boolean,
    val denial: DenialSettings,
    val logging: LoggingSettings,
    val profiles: Map<String, AccessProfile>,
    val servers: Map<String, ServerRule>,
    val unknownServers: Set<String>,
) {
    fun restrictionFor(server: String): ResolvedRestriction? {
        val configured = servers[server]
        if (configured != null) {
            if (!configured.enabled) return null
            return ResolvedRestriction(
                server = server,
                requirements = configured.requirements,
                bypassPermission = configured.bypassPermission,
                denial = denial.merge(configured.denial),
                profile = configured.profile,
                explicit = true,
            )
        }
        if (defaultPolicy == DefaultPolicy.OPEN) return null
        return ResolvedRestriction(
            server = server,
            requirements = Requirements(RequirementMode.ANY, listOf("lpbsa.server.$server"), emptyList()),
            bypassPermission = "lpbsa.bypass.$server",
            denial = denial.merge(DenialOverrides()),
            profile = null,
            explicit = false,
        )
    }
}

data class ResolvedRestriction(
    val server: String,
    val requirements: Requirements,
    val bypassPermission: String,
    val denial: ResolvedDenial,
    val profile: String?,
    val explicit: Boolean,
)
