package de.samthedev.lpbsa.config

import de.samthedev.lpbsa.message.MessageKeys
import de.samthedev.lpbsa.message.MessageTemplates
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.nio.file.Path

data class LoadedState(val state: RuntimeState, val warnings: List<String>)

class ConfigLoader(private val miniMessage: MiniMessage = MiniMessage.miniMessage()) {
    fun load(configPath: Path, messagesPath: Path, registeredServers: Set<String>): LoadedState {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val configRoot = loadYaml(configPath, "config.yml", problems)
        val messagesRoot = loadYaml(messagesPath, "messages.yml", problems)
        if (problems.isNotEmpty()) throw ConfigException(problems)

        val config = parseConfig(configRoot, registeredServers, problems, warnings)
        val messages = parseMessages(messagesRoot, problems)
        config.servers.values.filter { it.enabled }.forEach { rule ->
            val key = rule.denial.message ?: "server-access-denied"
            if (messages.template(key) == null) {
                problems += "servers.${rule.server}.denial.message: unknown message key \"$key\""
            }
        }
        if (problems.isNotEmpty()) throw ConfigException(problems)
        return LoadedState(RuntimeState(config, messages), warnings)
    }

    private fun loadYaml(path: Path, label: String, problems: MutableList<String>): ConfigurationNode {
        return try {
            YamlConfigurationLoader.builder().path(path).build().load()
        } catch (failure: Exception) {
            problems += "$label: ${failure.message ?: failure.javaClass.simpleName}"
            YamlConfigurationLoader.builder().path(path).build().createNode()
        }
    }

    private fun parseConfig(
        root: ConfigurationNode,
        registeredServers: Set<String>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): RuntimeConfig {
        val version = root.node("config-version").int
        if (version != SUPPORTED_CONFIG_VERSION) {
            problems += "config-version: expected $SUPPORTED_CONFIG_VERSION but found $version"
        }
        val settings = root.node("settings")
        val defaultPolicy = enum(settings.node("default-policy"), "settings.default-policy", DefaultPolicy.OPEN, problems)
        val failMode = enum(settings.node("fail-mode"), "settings.fail-mode", FailMode.CLOSED, problems)
        val strict = settings.node("strict-server-validation").getBoolean(false)
        val globalBypass = nonBlank(
            settings.node("global-bypass-permission").string ?: "lpbsa.bypass",
            "settings.global-bypass-permission",
            problems,
        ) ?: "lpbsa.bypass"
        val denial = parseGlobalDenial(settings.node("denial"), problems)
        val loggingNode = settings.node("logging")
        val logging = LoggingSettings(
            deniedConnections = loggingNode.node("denied-connections").getBoolean(true),
            allowedConnections = loggingNode.node("allowed-connections").getBoolean(false),
        )
        val profiles = parseProfiles(root.node("profiles"), problems, warnings)
        val servers = parseServers(root.node("servers"), profiles, problems, warnings)

        val referenced = buildSet {
            addAll(servers.keys)
            if (denial.initialAction == InitialAction.REDIRECT || denial.transferAction == TransferAction.REDIRECT) {
                add(denial.fallbackServer)
            }
            servers.values.mapNotNullTo(this) { it.denial.fallbackServer }
        }
        val unknown = referenced.filterNot { it in registeredServers }.toSet()
        unknown.forEach { warnings += "backend \"$it\" is not registered in Velocity" }
        if (strict && unknown.isNotEmpty()) {
            problems += unknown.sorted().map { "server-validation: unknown backend \"$it\"" }
        }

        return RuntimeConfig(
            configVersion = version,
            debug = root.node("debug").getBoolean(false),
            defaultPolicy = defaultPolicy,
            globalBypassPermission = globalBypass,
            failMode = failMode,
            strictServerValidation = strict,
            denial = denial,
            logging = logging,
            profiles = profiles,
            servers = servers,
            unknownServers = unknown,
        )
    }

    private fun parseGlobalDenial(node: ConfigurationNode, problems: MutableList<String>): DenialSettings {
        val cooldown = node.node("message-cooldown-ms").getLong(1_000)
        range(cooldown, 0, 600_000, "settings.denial.message-cooldown-ms", problems)
        return DenialSettings(
            transferAction = enum(node.node("transfer-action"), "settings.denial.transfer-action", TransferAction.STAY, problems),
            initialAction = enum(node.node("initial-action"), "settings.denial.initial-action", InitialAction.REDIRECT, problems),
            fallbackServer = node.node("fallback-server").string?.trim().orEmpty().ifBlank { "lobby" },
            notification = enum(node.node("notification"), "settings.denial.notification", NotificationLocation.CHAT, problems),
            messageCooldownMs = cooldown.coerceIn(0, 600_000),
        )
    }

    private fun parseProfiles(
        node: ConfigurationNode,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): Map<String, AccessProfile> = node.childrenMap().mapNotNull { (rawName, profileNode) ->
        val name = rawName.toString().trim()
        if (name.isBlank()) {
            problems += "profiles: profile names must not be blank"
            null
        } else {
            val requirementsNode = profileNode.node("requirements")
            if (requirementsNode.virtual()) problems += "profiles.$name.requirements: missing requirement set"
            val requirements = parseRequirements(requirementsNode, "profiles.$name.requirements", problems, warnings)
            if (requirements.entries.isEmpty()) problems += "profiles.$name.requirements: profile has zero valid requirements"
            name to AccessProfile(name, requirements)
        }
    }.toMap()

    private fun parseServers(
        node: ConfigurationNode,
        profiles: Map<String, AccessProfile>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): Map<String, ServerRule> = node.childrenMap().mapNotNull { (rawName, serverNode) ->
        val name = rawName.toString().trim()
        if (name.isBlank()) {
            problems += "servers: backend names must not be blank"
            return@mapNotNull null
        }
        val enabled = serverNode.node("enabled").getBoolean(false)
        val profileName = serverNode.node("profile").string?.trim()?.takeIf { it.isNotEmpty() }
        val requirementsNode = serverNode.node("requirements")
        val hasRequirements = !requirementsNode.virtual()
        if (profileName != null && hasRequirements) {
            problems += "servers.$name: profile and requirements cannot both be set"
        }
        val requirements = when {
            profileName != null -> profiles[profileName]?.requirements ?: run {
                problems += "servers.$name.profile: unknown profile \"$profileName\""
                Requirements(RequirementMode.ANY, emptyList(), emptyList())
            }
            hasRequirements -> parseRequirements(requirementsNode, "servers.$name.requirements", problems, warnings)
            else -> parseLegacyOrDefaultRequirements(serverNode, name, problems, warnings)
        }
        if (enabled && requirements.entries.isEmpty()) {
            warnings += "servers.$name.requirements: restricted server has zero valid requirements and will fail closed"
        }
        val bypass = serverNode.node("bypass-permission").string?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "lpbsa.bypass.$name"
        val denial = parseDenialOverrides(serverNode.node("denial"), "servers.$name.denial", problems)
        name to ServerRule(name, enabled, requirements, profileName, bypass, denial)
    }.toMap()

    private fun parseLegacyOrDefaultRequirements(
        node: ConfigurationNode,
        server: String,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): Requirements {
        val singular = node.node("permission").string?.trim()?.takeIf { it.isNotEmpty() }
        val plural = strings(node.node("permissions"), "servers.$server.permissions", problems, warnings)
        val configured = listOfNotNull(singular) + plural
        return Requirements(RequirementMode.ANY, configured.ifEmpty { listOf("lpbsa.server.$server") }, emptyList())
    }

    private fun parseRequirements(
        node: ConfigurationNode,
        path: String,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): Requirements = Requirements(
        mode = enum(node.node("mode"), "$path.mode", RequirementMode.ANY, problems),
        permissions = strings(node.node("permissions"), "$path.permissions", problems, warnings),
        groups = strings(node.node("groups"), "$path.groups", problems, warnings),
    )

    private fun parseDenialOverrides(
        node: ConfigurationNode,
        path: String,
        problems: MutableList<String>,
    ): DenialOverrides {
        if (node.virtual()) return DenialOverrides()
        val cooldownNode = node.node("message-cooldown-ms")
        val cooldown = if (cooldownNode.virtual()) null else cooldownNode.long.also {
            range(it, 0, 600_000, "$path.message-cooldown-ms", problems)
        }.coerceIn(0, 600_000)
        return DenialOverrides(
            transferAction = optionalEnum(node.node("transfer-action"), "$path.transfer-action", problems),
            initialAction = optionalEnum(node.node("initial-action"), "$path.initial-action", problems),
            fallbackServer = node.node("fallback-server").string?.trim()?.takeIf { it.isNotEmpty() },
            notification = optionalEnum(node.node("notification"), "$path.notification", problems),
            messageCooldownMs = cooldown,
            message = node.node("message").string?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseMessages(root: ConfigurationNode, problems: MutableList<String>): MessageTemplates {
        val version = root.node("config-version").int
        if (version != SUPPORTED_CONFIG_VERSION) {
            problems += "messages.yml config-version: expected $SUPPORTED_CONFIG_VERSION but found $version"
        }
        val prefix = root.node("prefix").string ?: ""
        val values = root.childrenMap().mapNotNull { (rawKey, valueNode) ->
            val key = rawKey.toString()
            if (key == "config-version" || key == "prefix") null else valueNode.string?.let { key to it }
        }.toMap()
        MessageKeys.required.filterNot(values::containsKey).forEach { problems += "messages.yml: missing message \"$it\"" }
        validateMiniMessage("prefix", prefix, problems)
        values.forEach { (key, template) -> validateMiniMessage(key, template, problems) }
        return MessageTemplates(version, prefix, values)
    }

    private fun validateMiniMessage(key: String, value: String, problems: MutableList<String>) {
        try {
            val placeholders = listOf(
                "prefix", "player", "server", "current_server", "permission", "permissions",
                "group", "groups", "fallback", "reason", "command", "description", "key", "value", "state",
                "version", "velocity", "luckperms", "java",
            )
            val resolver = TagResolver.builder().apply {
                placeholders.forEach { resolver(Placeholder.component(it, Component.text("value"))) }
            }.build()
            miniMessage.deserialize(value, resolver)
        } catch (failure: Exception) {
            problems += "messages.yml $key: invalid MiniMessage: ${failure.message ?: failure.javaClass.simpleName}"
        }
    }

    private fun strings(
        node: ConfigurationNode,
        path: String,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): List<String> {
        if (node.virtual()) return emptyList()
        val raw = try {
            node.getList(String::class.java) ?: emptyList()
        } catch (_: Exception) {
            problems += "$path: expected a YAML list of strings"
            return emptyList()
        }
        val values = raw.map(String::trim).filter(String::isNotEmpty).distinct()
        if (values.size != raw.size) warnings += "$path: ignored blank or duplicate entries"
        return values
    }

    private fun nonBlank(value: String, path: String, problems: MutableList<String>): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) problems += "$path: must not be blank"
        return trimmed.takeIf(String::isNotEmpty)
    }

    private fun range(value: Long, minimum: Long, maximum: Long, path: String, problems: MutableList<String>) {
        if (value !in minimum..maximum) problems += "$path: expected $minimum..$maximum but found $value"
    }

    private inline fun <reified T : Enum<T>> enum(
        node: ConfigurationNode,
        path: String,
        default: T,
        problems: MutableList<String>,
    ): T {
        if (node.virtual()) return default
        return optionalEnum<T>(node, path, problems) ?: default
    }

    private inline fun <reified T : Enum<T>> optionalEnum(
        node: ConfigurationNode,
        path: String,
        problems: MutableList<String>,
    ): T? {
        if (node.virtual()) return null
        val raw = node.string?.trim().orEmpty()
        return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: run {
            problems += "$path: expected ${enumValues<T>().joinToString(" or ") { it.name }} but found \"$raw\""
            null
        }
    }
}
