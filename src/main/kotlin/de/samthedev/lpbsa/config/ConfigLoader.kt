package de.samthedev.lpbsa.config

import de.samthedev.lpbsa.message.MessageKeys
import de.samthedev.lpbsa.message.MessageTemplates
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import org.spongepowered.configurate.yaml.internal.snakeyaml.LoaderOptions
import org.spongepowered.configurate.yaml.internal.snakeyaml.Yaml
import org.spongepowered.configurate.yaml.internal.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

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
            val safetyOptions = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                codePointLimit = MAX_YAML_CODE_POINTS
                nestingDepthLimit = MAX_YAML_NESTING_DEPTH
                maxAliasesForCollections = MAX_YAML_ALIASES
            }
            Files.newBufferedReader(path).use { reader ->
                Yaml(SafeConstructor(safetyOptions)).load<Any?>(reader)
            }
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
        requireMapping(root, "config.yml", problems)
        val version = integer(root.node("config-version"), "config-version", 0, problems)
        if (version != SUPPORTED_CONFIG_VERSION) {
            problems += "config-version: expected $SUPPORTED_CONFIG_VERSION but found $version"
        }
        val settings = root.node("settings")
        requireMapping(settings, "settings", problems)
        if (settings.node("default-policy").virtual()) {
            problems += "settings.default-policy: missing required value"
        }
        val defaultPolicy = enum(settings.node("default-policy"), "settings.default-policy", DefaultPolicy.OPEN, problems)
        val failMode = enum(settings.node("fail-mode"), "settings.fail-mode", FailMode.CLOSED, problems)
        val strict = boolean(settings.node("strict-server-validation"), "settings.strict-server-validation", false, problems)
        val globalBypass = nonBlank(
            string(settings.node("global-bypass-permission"), "settings.global-bypass-permission", "lpbsa.bypass", problems),
            "settings.global-bypass-permission",
            problems,
        ) ?: "lpbsa.bypass"
        val denial = parseGlobalDenial(settings.node("denial"), problems)
        val loggingNode = settings.node("logging")
        requireMapping(loggingNode, "settings.logging", problems, allowVirtual = true)
        val logging = LoggingSettings(
            deniedConnections = boolean(loggingNode.node("denied-connections"), "settings.logging.denied-connections", true, problems),
            allowedConnections = boolean(loggingNode.node("allowed-connections"), "settings.logging.allowed-connections", false, problems),
        )
        val profiles = parseProfiles(root.node("profiles"), problems, warnings)
        val servers = parseServers(root.node("servers"), profiles, problems, warnings)

        val registered = registeredServers.mapTo(mutableSetOf(), ::canonicalServerName)
        val referenced = buildSet {
            addAll(servers.keys)
            if (denial.initialAction == InitialAction.REDIRECT || denial.transferAction == TransferAction.REDIRECT) {
                add(denial.fallbackServer)
            }
            servers.values.mapNotNullTo(this) { it.denial.fallbackServer }
        }
        val unknown = referenced.filterNot { it in registered }.toSet()
        unknown.forEach { warnings += "backend \"$it\" is not registered in Velocity" }
        if (strict && unknown.isNotEmpty()) {
            problems += unknown.sorted().map { "server-validation: unknown backend \"$it\"" }
        }

        return RuntimeConfig(
            configVersion = version,
            debug = boolean(root.node("debug"), "debug", false, problems),
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
        requireMapping(node, "settings.denial", problems, allowVirtual = true)
        val cooldown = long(node.node("message-cooldown-ms"), "settings.denial.message-cooldown-ms", 1_000, problems)
        range(cooldown, 0, 600_000, "settings.denial.message-cooldown-ms", problems)
        return DenialSettings(
            transferAction = enum(node.node("transfer-action"), "settings.denial.transfer-action", TransferAction.STAY, problems),
            initialAction = enum(node.node("initial-action"), "settings.denial.initial-action", InitialAction.REDIRECT, problems),
            fallbackServer = serverName(node.node("fallback-server"), "settings.denial.fallback-server", "lobby", problems),
            notification = enum(node.node("notification"), "settings.denial.notification", NotificationLocation.CHAT, problems),
            messageCooldownMs = cooldown.coerceIn(0, 600_000),
        )
    }

    private fun parseProfiles(
        node: ConfigurationNode,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): Map<String, AccessProfile> {
        requireMapping(node, "profiles", problems, allowVirtual = true)
        return node.childrenMap().mapNotNull { (rawName, profileNode) ->
            val name = rawName.toString().trim()
            if (name.isBlank()) {
                problems += "profiles: profile names must not be blank"
                null
            } else {
                requireMapping(profileNode, "profiles.$name", problems)
                val requirementsNode = profileNode.node("requirements")
                if (requirementsNode.virtual()) problems += "profiles.$name.requirements: missing requirement set"
                requireMapping(requirementsNode, "profiles.$name.requirements", problems, allowVirtual = true)
                val requirements = parseRequirements(requirementsNode, "profiles.$name.requirements", problems, warnings)
                if (requirements.entries.isEmpty()) problems += "profiles.$name.requirements: profile has zero valid requirements"
                name to AccessProfile(name, requirements)
            }
        }.toMap()
    }

    private fun parseServers(
        node: ConfigurationNode,
        profiles: Map<String, AccessProfile>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): Map<String, ServerRule> {
        requireMapping(node, "servers", problems)
        val result = linkedMapOf<String, ServerRule>()
        node.childrenMap().forEach { (rawName, serverNode) ->
            val configuredName = rawName.toString().trim()
            val name = canonicalServerName(configuredName)
            if (name.isBlank()) {
                problems += "servers: backend names must not be blank"
                return@forEach
            }
            if (result.containsKey(name)) {
                problems += "servers.$configuredName: duplicates backend \"$name\" case-insensitively"
                return@forEach
            }
            requireMapping(serverNode, "servers.$configuredName", problems)
            if (serverNode.node("enabled").virtual()) {
                problems += "servers.$configuredName.enabled: missing required value"
            }
            val enabled = boolean(serverNode.node("enabled"), "servers.$configuredName.enabled", false, problems)
            val profileName = optionalString(serverNode.node("profile"), "servers.$configuredName.profile", problems)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val requirementsNode = serverNode.node("requirements")
            val hasRequirements = !requirementsNode.virtual()
            if (profileName != null && hasRequirements) {
                problems += "servers.$configuredName: profile and requirements cannot both be set"
            }
            if (hasRequirements) requireMapping(requirementsNode, "servers.$configuredName.requirements", problems)
            val requirements = when {
                profileName != null -> profiles[profileName]?.requirements ?: run {
                    problems += "servers.$configuredName.profile: unknown profile \"$profileName\""
                    Requirements(RequirementMode.ANY, emptyList(), emptyList())
                }
                hasRequirements -> parseRequirements(requirementsNode, "servers.$configuredName.requirements", problems, warnings)
                else -> parseLegacyOrDefaultRequirements(serverNode, name, problems, warnings)
            }
            if (enabled && requirements.entries.isEmpty()) {
                warnings += "servers.$configuredName.requirements: restricted server has zero valid requirements and will fail closed"
            }
            val bypass = optionalString(serverNode.node("bypass-permission"), "servers.$configuredName.bypass-permission", problems)
                ?.let { nonBlank(it, "servers.$configuredName.bypass-permission", problems) }
                ?: "lpbsa.bypass.$name"
            val denial = parseDenialOverrides(serverNode.node("denial"), "servers.$configuredName.denial", problems)
            result[name] = ServerRule(name, enabled, requirements, profileName, bypass, denial)
        }
        return result.toMap()
    }

    private fun parseLegacyOrDefaultRequirements(
        node: ConfigurationNode,
        server: String,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): Requirements {
        val singular = optionalString(node.node("permission"), "servers.$server.permission", problems)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val plural = strings(node.node("permissions"), "servers.$server.permissions", problems, warnings)
        val configured = (listOfNotNull(singular) + plural).distinct()
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
        requireMapping(node, path, problems)
        val cooldownNode = node.node("message-cooldown-ms")
        val cooldown = if (cooldownNode.virtual()) null else long(cooldownNode, "$path.message-cooldown-ms", 0, problems).also {
            range(it, 0, 600_000, "$path.message-cooldown-ms", problems)
        }.coerceIn(0, 600_000)
        return DenialOverrides(
            transferAction = optionalEnum(node.node("transfer-action"), "$path.transfer-action", problems),
            initialAction = optionalEnum(node.node("initial-action"), "$path.initial-action", problems),
            fallbackServer = if (node.node("fallback-server").virtual()) null else
                serverName(node.node("fallback-server"), "$path.fallback-server", "", problems).takeIf { it.isNotEmpty() },
            notification = optionalEnum(node.node("notification"), "$path.notification", problems),
            messageCooldownMs = cooldown,
            message = optionalString(node.node("message"), "$path.message", problems)
                ?.let { nonBlank(it, "$path.message", problems) },
        )
    }

    private fun parseMessages(root: ConfigurationNode, problems: MutableList<String>): MessageTemplates {
        requireMapping(root, "messages.yml", problems)
        val version = integer(root.node("config-version"), "messages.yml config-version", 0, problems)
        if (version != SUPPORTED_CONFIG_VERSION) {
            problems += "messages.yml config-version: expected $SUPPORTED_CONFIG_VERSION but found $version"
        }
        val prefix = string(root.node("prefix"), "messages.yml prefix", "", problems)
        val values = root.childrenMap().mapNotNull { (rawKey, valueNode) ->
            val key = rawKey.toString()
            if (key == "config-version" || key == "prefix") null else {
                val value = optionalString(valueNode, "messages.yml $key", problems)
                value?.let { key to it }
            }
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
        if (!node.isList) {
            problems += "$path: expected a YAML list of strings"
            return emptyList()
        }
        val raw = node.childrenList().mapNotNull { child ->
            val value = child.raw()
            if (value is String) value else {
                problems += "$path: expected a YAML list of strings"
                null
            }
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

    private fun boolean(
        node: ConfigurationNode,
        path: String,
        default: Boolean,
        problems: MutableList<String>,
    ): Boolean {
        if (node.virtual()) return default
        return when (val raw = node.raw()) {
            is Boolean -> raw
            is String -> when (raw.trim().lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        } ?: run {
            problems += "$path: expected true or false but found \"${node.raw()}\""
            default
        }
    }

    private fun long(
        node: ConfigurationNode,
        path: String,
        default: Long,
        problems: MutableList<String>,
    ): Long {
        if (node.virtual()) return default
        val value = when (val raw = node.raw()) {
            is Byte, is Short, is Int, is Long -> raw.toString().toLongOrNull()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        if (value == null) problems += "$path: expected an integer but found \"${node.raw()}\""
        return value ?: default
    }

    private fun integer(
        node: ConfigurationNode,
        path: String,
        default: Int,
        problems: MutableList<String>,
    ): Int {
        val value = long(node, path, default.toLong(), problems)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
            problems += "$path: integer is out of range"
            return default
        }
        return value.toInt()
    }

    private fun string(
        node: ConfigurationNode,
        path: String,
        default: String,
        problems: MutableList<String>,
    ): String {
        if (node.virtual()) return default
        val value = node.raw()
        if (value !is String) {
            problems += "$path: expected a string"
            return default
        }
        return value
    }

    private fun optionalString(
        node: ConfigurationNode,
        path: String,
        problems: MutableList<String>,
    ): String? {
        if (node.virtual()) return null
        val value = node.raw()
        if (value !is String) {
            problems += "$path: expected a string"
            return null
        }
        return value
    }

    private fun serverName(
        node: ConfigurationNode,
        path: String,
        default: String,
        problems: MutableList<String>,
    ): String {
        val value = string(node, path, default, problems)
        val canonical = canonicalServerName(value)
        if (!node.virtual() && canonical.isEmpty()) problems += "$path: must not be blank"
        return canonical
    }

    private fun requireMapping(
        node: ConfigurationNode,
        path: String,
        problems: MutableList<String>,
        allowVirtual: Boolean = false,
    ) {
        if (allowVirtual && node.virtual()) return
        if (!node.isMap) problems += "$path: expected a YAML mapping"
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
        val rawValue = node.raw()
        if (rawValue !is String) {
            problems += "$path: expected ${enumValues<T>().joinToString(" or ") { it.name }} but found a non-string value"
            return null
        }
        val raw = rawValue.trim()
        return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: run {
            problems += "$path: expected ${enumValues<T>().joinToString(" or ") { it.name }} but found \"$raw\""
            null
        }
    }

    private companion object {
        const val MAX_YAML_CODE_POINTS = 1_000_000
        const val MAX_YAML_NESTING_DEPTH = 50
        const val MAX_YAML_ALIASES = 50
    }
}
